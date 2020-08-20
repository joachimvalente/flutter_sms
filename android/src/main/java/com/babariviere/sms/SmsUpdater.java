package com.babariviere.sms;

import static io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import static io.flutter.plugin.common.MethodChannel.Result;
import static io.flutter.plugin.common.PluginRegistry.RequestPermissionsResultListener;

import android.Manifest;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import com.babariviere.sms.permisions.Permissions;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.PluginRegistry;
import java.util.ArrayList;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Created by babariviere on 09/03/18.
 */

class SmsUpdater implements RequestPermissionsResultListener {
  private final PluginRegistry.Registrar registrar;
  private final String[] permissionsList = new String[] {
    Manifest.permission.SEND_SMS,
  };
  private MethodChannel.Result result;
  private String messageId = null;

  SmsUpdater(
    PluginRegistry.Registrar registrar,
    MethodChannel.Result result,
    String messageId
  ) {
    this.registrar = registrar;
    this.result = result;
    this.messageId = messageId;
  }

  void handleMarkMessageRead(Permissions permissions) {
    if (
      permissions.checkAndRequestPermission(
        permissionsList,
        Permissions.SEND_SMS_ID_REQ
      )
    ) {
      markMessageRead();
    }
  }

  private void markMessageRead() {
    ContentValues values = new ContentValues();
    values.put("read", true);
    registrar
      .context()
      .getContentResolver()
      .update(
        Uri.parse("content://sms/inbox"),
        values,
        "_id=" + messageId,
        null
      );
    result.success(null);
  }

  @Override
  public boolean onRequestPermissionsResult(
    int requestCode,
    String[] permissions,
    int[] grantResults
  ) {
    if (requestCode != Permissions.SEND_SMS_ID_REQ) {
      return false;
    }
    boolean isOk = true;
    for (int res : grantResults) {
      if (res != PackageManager.PERMISSION_GRANTED) {
        isOk = false;
        break;
      }
    }
    if (isOk) {
      markMessageRead();
      return true;
    }
    result.error("#01", "permission denied", null);
    return false;
  }
}

class SmsUpdate implements MethodCallHandler {
  private final PluginRegistry.Registrar registrar;
  private final Permissions permissions;

  SmsUpdate(PluginRegistry.Registrar registrar) {
    this.registrar = registrar;
    this.permissions = new Permissions(registrar.activity());
  }

  @Override
  public void onMethodCall(MethodCall call, Result result) {
    String messageId = null;
    switch (call.method) {
      case "markAsRead":
        if (call.hasArgument("messageId")) {
          messageId = call.argument("messageId");
        }
        break;
    }
    SmsUpdater handler = new SmsUpdater(registrar, result, messageId);
    this.registrar.addRequestPermissionsResultListener(handler);
    handler.handle(permissions);
  }
}
