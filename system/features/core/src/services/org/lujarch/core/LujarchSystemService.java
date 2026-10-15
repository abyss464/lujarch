package org.lujarch.core;

import android.content.Context;

import com.android.server.SystemService;

/** Lujarch's services in system_server, started by SystemServer from lujarch-services.jar. */
public final class LujarchSystemService extends SystemService {
    private static final String MIRROR_LIST = "/system_ext/etc/lujarch/settings-mirror.txt";

    public LujarchSystemService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {}

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_SYSTEM_SERVICES_READY) {
            new SettingsMirror(getContext(), MIRROR_LIST).start();
        }
    }
}
