package com.gathin.tvhome;

import android.service.notification.NotificationListenerService;

/**
 * Exists only so the launcher may read the active media sessions ({@link ContentShelf}): Android hands
 * them to an enabled notification listener and to nobody else outside the firmware. It reads no
 * notifications. Off until enabled over adb:
 * {@code cmd notification allow_listener com.gathin.tvhome/com.gathin.tvhome.MediaListener}
 */
public class MediaListener extends NotificationListenerService {
}
