# Google's services from MindTheGapps (APP-1): Play services, Google Services Framework and the
# Play Store with what they need to run, and nothing else of Google's.
PRODUCT_SOONG_NAMESPACES += \
    vendor/gapps/$(LUJARCH_ARCH) \
    vendor/gapps/common \
    vendor/gapps/overlay

PRODUCT_PACKAGES += \
    GoogleServicesFramework \
    Phonesky \
    GmsOverlay \
    GmsSettingsOverlay \
    GmsSettingsProviderOverlay \
    default-permissions-google.xml \
    default-permissions-mtg.xml \
    gapps.rc \
    gms_fsverity_cert.der \
    google-hiddenapi-package-allowlist.xml \
    google.xml \
    privapp-permissions-google-product.xml \
    privapp-permissions-google-system_ext.xml \
    privapp-permissions-mtg.xml \
    sysconfig-mtg.xml

ifeq ($(LUJARCH_ARCH),arm64)
PRODUCT_PACKAGES += \
    allowed_apex_com.google.android.gmssystem.xml \
    com.google.android.gmssystem.prodvic
else
PRODUCT_PACKAGES += \
    GmsCore
endif

# LineageOS' camera and audio effects are not in the image (APP-4).
PRODUCT_NO_CAMERA := true
TARGET_EXCLUDES_AUDIOFX := true
