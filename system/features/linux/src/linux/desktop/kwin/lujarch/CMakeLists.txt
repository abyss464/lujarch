# The Lujarch output backend: KWin renders into buffers that the Terminal app allocates and
# presents (lujarch_display.h). Built into libkwin like the other backends; selected with
# --lujarch or KWIN_LUJARCH=1. lujarch_display.h is copied next to these files (PKGBUILD
# prepare), so quoted includes find it without another include directory on the kwin target (one
# would change every object's flags).
target_sources(kwin PRIVATE
    lujarch_backend.cpp
    lujarch_connection.cpp
    lujarch_egl_backend.cpp
    lujarch_input.cpp
    lujarch_logging.cpp
    lujarch_output.cpp
)
