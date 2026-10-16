/*
    KWin - the KDE window manager
    This file is part of the KDE project.

    SPDX-FileCopyrightText: 2026 The Lujarch Project

    SPDX-License-Identifier: GPL-2.0-or-later
*/
#include "lujarch_logging.h"

// Info and above by default: connection, pool and configure changes are rare and worth a line each.
Q_LOGGING_CATEGORY(KWIN_LUJARCH, "kwin_backend_lujarch", QtInfoMsg)
