# Build actions see only an allowlist of the environment; the dates in build.prop and the kernel's
# version are formatted inside them and must be in UTC.
BUILD_BROKEN_NINJA_USES_ENV_VARS += TZ

BOARD_VENDOR_SEPOLICY_DIRS += vendor/extra/device/fuxi/sepolicy/vendor
TARGET_SYSTEM_EXT_PROP += vendor/extra/device/fuxi/system_ext.prop
