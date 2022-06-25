package com.android.server.lights;

import android.os.AuraLightEffect;
import android.service.notification.StatusBarNotification;
import java.util.List;

public interface AuraLightManagerInternal
{
    void notifyBatteryStatsReset();
    void notifyLidSwitchChanged(long j, boolean z);
    void setCustomEffect(int i, int i2, List<AuraLightEffect> list);
    void setFocusedApp(String str, String str2);
    void updateNotificationLighting(List<StatusBarNotification> list);
}
