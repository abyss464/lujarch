PRODUCT_PACKAGES += \
    lujarch_cpu.rc

# Settings > Battery shows Lowest CPU frequency when idle where the floor is written.
PRODUCT_SYSTEM_EXT_PROPERTIES += \
    ro.lujarch.cpu.idle_floor=true
