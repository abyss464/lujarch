# Google's services from MindTheGapps (APP-1): Play services, Google Services Framework and the
# Play Store with what they need to run, and nothing else of Google's.
# MindTheGapps' Play services for x86_64 predates Android 17's services framework, so only arm64
# products have them.
ifeq ($(LUJARCH_ARCH),arm64)
PRODUCT_SOONG_NAMESPACES += \
    vendor/gapps/arm64 \
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

PRODUCT_PACKAGES += \
    allowed_apex_com.google.android.gmssystem.xml \
    com.google.android.gmssystem.prodvic
endif
