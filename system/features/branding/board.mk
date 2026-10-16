SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS += vendor/extra/features/branding/sepolicy/private

# Recovery's and fastbootd's images drawn from the mark (BRD-3), over recovery's own; a device's
# own recovery images stay first.
TARGET_PRIVATE_RES_DIRS = $(wildcard $(TARGET_DEVICE_DIR)/recovery/res) \
    vendor/extra/features/branding/generated/recovery/res
