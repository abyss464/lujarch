package org.lujarch.files

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.ext.SdkExtensions
import android.provider.MediaStore

/**
 * GET_CONTENT for media goes to the photo picker exactly as DocumentsUI's TrampolineActivity
 * decides (packages/apps/DocumentsUI, picker/TrampolineActivity.kt and
 * util/ComponentUtils.kt): the photo picker is the package answering ACTION_PICK_IMAGES, its
 * enabled GET_CONTENT activity for the request's type takes the request when every requested
 * type is an image or a video, unless the photo picker itself sent the request here (its
 * "Browse" and its own referral of non-media requests, which resolve OPEN_DOCUMENT to find this
 * app). Everything else opens Files.
 */
internal object PhotoPicker {
    /** The photo picker's GET_CONTENT activity for [type], or null when there is none enabled. */
    fun component(pm: PackageManager, type: String?, self: String): ComponentName? {
        // ACTION_PICK_IMAGES exists from SdkExtensions R 2 on.
        if (SdkExtensions.getExtensionVersion(Build.VERSION_CODES.R) < 2) return null
        val picker = Intent(MediaStore.ACTION_PICK_IMAGES).addCategory(Intent.CATEGORY_DEFAULT)
            .resolveActivity(pm) ?: return null
        // Without a package, GET_CONTENT would resolve to this app again.
        if (picker.packageName == self) return null
        val getContent = Intent(Intent.ACTION_GET_CONTENT).setType(type).setPackage(picker.packageName)
            .resolveActivity(pm) ?: return null
        return getContent.takeIf { enabled(pm, it) }
    }

    /** Whether the photo picker handles a GET_CONTENT request: only media types, and named. */
    fun handles(intent: Intent): Boolean {
        if (intent.action != Intent.ACTION_GET_CONTENT) return false
        val type = intent.type
        val typeIsMedia = isMedia(type)
        if (!typeIsMedia && type != "*/*") return false
        val extra = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)
        if (type == "*/*" && extra == null) return false
        if (extra == null) return typeIsMedia
        return extra.isNotEmpty() && extra.all { isMedia(it) }
    }

    private fun isMedia(type: String?): Boolean =
        type != null && (type.startsWith("image/") || type.startsWith("video/"))

    /** The photo picker disables its activities in some states; DEFAULT defers to the manifest. */
    private fun enabled(pm: PackageManager, c: ComponentName): Boolean =
        when (pm.getComponentEnabledSetting(c)) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> try {
                pm.getPackageInfo(c.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES.toLong()))
                    .activities.orEmpty().firstOrNull { it.name == c.className }?.enabled == true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
            else -> false
        }
}
