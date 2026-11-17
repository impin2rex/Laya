package com.music.laya.desktop.notify;

import java.util.List;
import java.util.Map;

import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.DBusInterface;
import org.freedesktop.dbus.messages.DBusSignal;
import org.freedesktop.dbus.types.UInt32;
import org.freedesktop.dbus.types.Variant;

/** The freedesktop notification server, as much of it as one notification with buttons needs. */
@DBusInterfaceName("org.freedesktop.Notifications")
public interface Notifications extends DBusInterface {

    String BUS_NAME = "org.freedesktop.Notifications";
    String OBJECT_PATH = "/org/freedesktop/Notifications";

    /** [actions]: key, label, key, label… — "default" is a click on the notification itself. */
    UInt32 Notify(String appName, UInt32 replacesId, String appIcon, String summary, String body,
                  List<String> actions, Map<String, Variant<?>> hints, int expireTimeout);

    /** A button, or the notification itself, was clicked. */
    final class ActionInvoked extends DBusSignal {
        public final UInt32 id;
        public final String actionKey;

        public ActionInvoked(String path, UInt32 id, String actionKey) throws DBusException {
            super(path, id, actionKey);
            this.id = id;
            this.actionKey = actionKey;
        }
    }
}
