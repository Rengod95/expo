package expo.modules.updates.launcher

import android.content.Context
import android.net.Uri
import expo.modules.updates.UpdatesConfiguration
import expo.modules.updates.UpdatesUtils
import expo.modules.updates.db.UpdatesDatabase
import expo.modules.updates.db.entity.AssetEntity
import expo.modules.updates.db.entity.UpdateEntity
import expo.modules.updates.db.enums.UpdateStatus
import expo.modules.updates.launcher.Launcher.LauncherCallback
import expo.modules.updates.loader.FileDownloader
import expo.modules.updates.loader.FileDownloader.AssetDownloadCallback
import expo.modules.updates.loader.LoaderFiles
import expo.modules.updates.logging.UpdatesErrorCode
import expo.modules.updates.logging.UpdatesLogger
import expo.modules.updates.manifest.EmbeddedManifestUtils
import expo.modules.updates.manifest.EmbeddedUpdate
import expo.modules.updates.manifest.ManifestMetadata
import expo.modules.updates.selectionpolicy.SelectionPolicy
import org.json.JSONObject
import java.io.File

/**
 * Implementation of [Launcher] that uses the SQLite database and expo-updates file store as the
 * source of updates.
 *
 * Uses the [SelectionPolicy] to choose an update from SQLite to launch, then ensures that the
 * update is safe and ready to launch (i.e. all the assets that SQLite expects to be stored on disk
 * are actually there).
 *
 * This class also includes failsafe code to attempt to re-download any assets unexpectedly missing
 * from disk (since it isn't necessarily safe to just revert to an older update in this case).
 * Distinct from the Loader classes, though, this class does *not* make any major modifications to
 * the database; its role is mostly to read the database and ensure integrity with the file system.
 *
 * It's important that the update to launch is selected *before* any other checks, e.g. the above
 * check for assets on disk. This is to preserve the invariant that no older update should ever be
 * launched after a newer one has been launched.
 */
class DatabaseLauncher(
  private val context: Context,
  private val configuration: UpdatesConfiguration,
  private val updatesDirectory: File?,
  private val fileDownloader: FileDownloader,
  private val selectionPolicy: SelectionPolicy,
  private val logger: UpdatesLogger
) : Launcher {
  private val loaderFiles: LoaderFiles = LoaderFiles()
  override var launchedUpdate: UpdateEntity? = null
    private set
  override var launchAssetFile: String? = null
    private set
  override var bundleAssetName: String? = null
    private set
  override var localAssetFiles: MutableMap<AssetEntity, String>? = null
    private set
  override val isUsingEmbeddedAssets: Boolean
    get() = localAssetFiles == null

  private var assetsToDownload = 0
  private var assetsToDownloadFinished = 0
  private var launchAssetException: Exception? = null
  private var callback: LauncherCallback? = null

  @Synchronized
  fun launch(database: UpdatesDatabase, callback: LauncherCallback?) {
    if (this.callback != null) {
      throw AssertionError("DatabaseLauncher has already started. Create a new instance in order to launch a new version.")
    }
    this.callback = callback

    launchedUpdate = getLaunchableUpdate(database)
    if (launchedUpdate == null) {
      this.callback!!.onFailure(Exception("No launchable update was found. If this is a generic app, ensure expo-updates is configured correctly."))
      return
    }

    database.updateDao().markUpdateAccessed(launchedUpdate!!)
    if (launchedUpdate!!.status == UpdateStatus.DEVELOPMENT) {
      this.callback!!.onSuccess()
      return
    }

    // verify that we have all assets on disk
    // according to the database, we should, but something could have gone wrong on disk
    val launchAsset = database.updateDao().loadLaunchAssetForUpdate(launchedUpdate!!.id)
    if (launchAsset == null) {
      this.callback!!.onFailure(Exception("Launch asset not found for update; this should never happen. Debug info: ${launchedUpdate!!.debugInfo()}"))
      return
    }

    if (launchAsset.relativePath == null) {
      this.callback!!.onFailure(Exception("Launch asset relative path should not be null. Debug info: ${launchedUpdate!!.debugInfo()}"))
    }

    val embeddedUpdate = EmbeddedManifestUtils.getEmbeddedUpdate(context, configuration)
    val extraHeaders = FileDownloader.getExtraHeadersForRemoteAssetRequest(launchedUpdate, embeddedUpdate?.updateEntity, launchedUpdate)

    val launchAssetFile = ensureAssetExists(launchAsset, database, embeddedUpdate, extraHeaders)
    if (launchAssetFile != null) {
      this.launchAssetFile = launchAssetFile.toString()
    }

    val assetEntities = database.assetDao().loadAssetsForUpdate(launchedUpdate!!.id)

    localAssetFiles = embeddedAssetFileMap().apply {
      for (asset in assetEntities) {
        if (asset.id == launchAsset.id) {
          // we took care of this one above
          continue
        }
        val filename = asset.relativePath
        if (filename != null) {
          val assetFile = ensureAssetExists(asset, database, embeddedUpdate, extraHeaders)
          if (assetFile != null) {
            this[asset] = Uri.fromFile(assetFile).toString()
          }
        }
      }
    }

    if (assetsToDownload == 0) {
      if (this.launchAssetFile == null) {
        this.callback!!.onFailure(Exception("Launch asset file was null with no assets to download reported; this should never happen. Debug info: ${launchedUpdate!!.debugInfo()}"))
      } else {
        this.callback!!.onSuccess()
      }
    }
  }

  fun getLaunchableUpdate(database: UpdatesDatabase): UpdateEntity? {
    val launchableUpdates = database.updateDao().loadLaunchableUpdatesForScope(configuration.scopeKey)

    // We can only run an update marked as embedded if it's actually the update embedded in the
    // current binary. We might have an older update from a previous binary still listed as
    // "EMBEDDED" in the database so we need to do this check.
    val embeddedUpdate = EmbeddedManifestUtils.getEmbeddedUpdate(context, configuration)
    val filteredLaunchableUpdates = mutableListOf<UpdateEntity>()
    for (update in launchableUpdates) {
      if (update.status == UpdateStatus.EMBEDDED) {
        if (embeddedUpdate != null && embeddedUpdate.updateEntity.id != update.id) {
          continue
        }
      }
      filteredLaunchableUpdates.add(update)
    }
    val manifestFilters = ManifestMetadata.getManifestFilters(database, configuration)
    return selectionPolicy.selectUpdateToLaunch(filteredLaunchableUpdates, manifestFilters)
  }

  private fun embeddedAssetFileMap(): MutableMap<AssetEntity, String> {
    val embeddedManifest = EmbeddedManifestUtils.getEmbeddedUpdate(context, configuration)
    val embeddedAssets = embeddedManifest?.assetEntityList ?: listOf()
    logger.info("embeddedAssetFileMap: embeddedAssets count = ${embeddedAssets.count()}")
    return mutableMapOf<AssetEntity, String>().apply {
      for (asset in embeddedAssets) {
        if (asset.isLaunchAsset) {
          continue
        }
        val filename = UpdatesUtils.createFilenameForAsset(asset)
        asset.relativePath = filename
        val assetFile = File(updatesDirectory, filename)
        if (!assetFile.exists()) {
          loaderFiles.copyAssetAndGetHash(asset, assetFile, context)
        }
        if (assetFile.exists()) {
          this[asset] = Uri.fromFile(assetFile).toString()
          logger.info("embeddedAssetFileMap: ${asset.key},${asset.type} => ${this[asset]}")
        } else {
          val cause = Exception("Missing embedded asset")
          logger.error("embeddedAssetFileMap: no file for ${asset.key},${asset.type}", cause, UpdatesErrorCode.AssetsFailedToLoad)
        }
      }
    }
  }

  fun ensureAssetExists(asset: AssetEntity, database: UpdatesDatabase, embeddedUpdate: EmbeddedUpdate?, extraHeaders: JSONObject): File? {
    val assetFile = File(updatesDirectory, asset.relativePath ?: "")
    var assetFileExists = assetFile.exists()
    if (!assetFileExists) {
      // something has gone wrong, we're missing this asset
      // first we check to see if a copy is embedded in the binary
      if (embeddedUpdate != null) {
        val embeddedAssets = embeddedUpdate.assetEntityList
        var matchingEmbeddedAsset: AssetEntity? = null
        for (embeddedAsset in embeddedAssets) {
          if (embeddedAsset.key != null && embeddedAsset.key == asset.key) {
            matchingEmbeddedAsset = embeddedAsset
            break
          }
        }

        if (matchingEmbeddedAsset != null) {
          try {
            val hash = loaderFiles.copyAssetAndGetHash(matchingEmbeddedAsset, assetFile, context)
            if (hash.contentEquals(asset.hash)) {
              assetFileExists = true
            }
          } catch (e: Exception) {
            // things are really not going our way...
            logger.error("Failed to copy matching embedded asset", e, UpdatesErrorCode.AssetsFailedToLoad)
          }
        }
      }
    }

    return if (!assetFileExists) {
      // we still don't have the asset locally, so try downloading it remotely
      assetsToDownload++

      fileDownloader.downloadAsset(
        asset,
        updatesDirectory,
        extraHeaders,
        object : AssetDownloadCallback {
          override fun onFailure(e: Exception, assetEntity: AssetEntity) {
            logger.error("Failed to load asset from disk or network", e, UpdatesErrorCode.AssetsFailedToLoad)
            if (assetEntity.isLaunchAsset) {
              launchAssetException = e
            }
            maybeFinish(assetEntity, null)
          }

          override fun onSuccess(assetEntity: AssetEntity, isNew: Boolean) {
            database.assetDao().updateAsset(assetEntity)
            val assetFileLocal = File(updatesDirectory, assetEntity.relativePath!!)
            maybeFinish(assetEntity, if (assetFileLocal.exists()) assetFileLocal else null)
          }
        }
      )
      null
    } else {
      assetFile
    }
  }

  @Synchronized
  private fun maybeFinish(asset: AssetEntity, assetFile: File?) {
    assetsToDownloadFinished++
    if (asset.isLaunchAsset) {
      launchAssetFile = assetFile?.toString()
    } else {
      if (assetFile != null) {
        localAssetFiles!![asset] = assetFile.toString()
      }
    }
    if (assetsToDownloadFinished == assetsToDownload) {
      if (launchAssetFile == null) {
        if (launchAssetException == null) {
          launchAssetException = Exception("Launcher launch asset file is unexpectedly null")
        }
        callback!!.onFailure(launchAssetException!!)
      } else {
        callback!!.onSuccess()
      }
    }
  }

  companion object {
    private val TAG = DatabaseLauncher::class.java.simpleName
  }
}
