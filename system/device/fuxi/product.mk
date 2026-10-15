# The Xiaomi 13 runs 64-bit code only, but its product inherits core_64_bit.mk, which leaves
# artd compiling with the 32-bit dex2oat the ART module does not ship.
PRODUCT_VENDOR_PROPERTIES += dalvik.vm.dex2oat64.enabled=true

LUJARCH_FUXI_STOCK := vendor/extra/device/fuxi/stock

PRODUCT_PRIVATE_SEPOLICY_DIRS += vendor/extra/device/fuxi/sepolicy/private

# Subsystem restart, and the embedded secure element's power manager, which the secure element
# HAL needs before it opens the element.
PRODUCT_PACKAGES += \
    lujarch_fuxi_esepowermanager \
    lujarch_fuxi_ssr_setup
PRODUCT_COPY_FILES += \
    $(LUJARCH_FUXI_STOCK)/vendor/etc/init/vendor.qti.esepowermanager@1.1-service.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/vendor.qti.esepowermanager@1.1-service.rc

# Thermal settings of the replacement battery cell, which LineageOS' vendor project lacks.
PRODUCT_COPY_FILES += \
    $(foreach f,$(notdir $(wildcard $(LUJARCH_FUXI_STOCK)/odm/etc/thermal-*.conf)),$(LUJARCH_FUXI_STOCK)/odm/etc/$(f):$(TARGET_COPY_OUT_ODM)/etc/$(f))

# Xiaomi's camera. Its final image is reprocessed through a stream size the camera does not
# advertise, which LineageOS' camera service accepts only from packages listed here.
PRODUCT_PACKAGES += \
    LujarchFuxiCameraOverlay \
    MiuiCamera \
    lujarch_fuxi_camera_algoup_jni \
    lujarch_fuxi_camera_campostproc \
    lujarch_fuxi_camera_hiddenapi_allowlist \
    lujarch_fuxi_camera_mianode_jni \
    lujarch_fuxi_camera_postproc_client \
    lujarch_fuxi_camera_privapp_permissions \
    lujarch_fuxi_camera_public_libraries
PRODUCT_VENDOR_PROPERTIES += \
    persist.vendor.camera.privapp.list=com.android.camera
