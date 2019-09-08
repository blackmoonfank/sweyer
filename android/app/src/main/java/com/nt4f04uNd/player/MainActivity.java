package com.nt4f04uNd.player;

import android.os.Bundle;
import io.flutter.app.FlutterActivity;
import io.flutter.plugins.GeneratedPluginRegistrant;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.media.AudioManager;
import android.media.AudioFocusRequest;
import android.util.Log;
import io.flutter.plugin.common.EventChannel;

// Method channel
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;

import java.util.List;
import java.util.ArrayList;
import android.provider.MediaStore;
import android.database.Cursor;
import android.net.Uri;
import android.content.ContentUris;

import android.app.NotificationManager;
import android.app.NotificationChannel;
import android.app.PendingIntent;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.NotificationCompat;
import android.os.Build;

import android.view.KeyEvent;
import android.media.session.MediaSession;

public class MainActivity extends FlutterActivity {
   private static String TAG = "player/java file";

   private static final String eventChannelStream = "eventChannelStream";
   private static final String methodChannelStream = "methodChannelStream";

   private static IntentFilter notificationIntentFilter = new IntentFilter();
   private static IntentFilter noisyIntentFilter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);

   private static PlayerReceiver myPlayerReceiver = null;
   private static BecomingNoisyReceiver myNoisyAudioStreamReceiver = null;

   NotificationManagerCompat notificationManager;
   PendingIntent playPendingIntent;
   PendingIntent pausePendingIntent;
   PendingIntent prevPendingIntent;
   PendingIntent nextPendingIntent;
   PendingIntent pendingNotificationIntent;

   /** Focus request for audio manager */
   private static AudioFocusRequest focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
         .setAcceptsDelayedFocusGain(true).setOnAudioFocusChangeListener(new OnFocusChangeListener()).build();
   private static MethodChannel methodChannel;
   private static EventChannel eventChannel;

   private AudioManager audioManager;
   private MediaSession audioSession;

   /** Request audio manager focus for app */
   private String requestFocus() {
      int res = audioManager.requestAudioFocus(focusRequest);
      Log.w(TAG, "REQUEST FOCUS " + res);
      if (res == AudioManager.AUDIOFOCUS_REQUEST_FAILED) {
         return "AUDIOFOCUS_REQUEST_FAILED";
      } else if (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
         return "AUDIOFOCUS_REQUEST_GRANTED";
      } else if (res == AudioManager.AUDIOFOCUS_REQUEST_DELAYED) {
         return "AUDIOFOCUS_REQUEST_DELAYED";
      }
      return "WRONG_EVENT";
   }

   private int abandonFocus() {
      int res = audioManager.abandonAudioFocusRequest(focusRequest);
      Log.w(TAG, "ABANDON FOCUS " + res);
      return res;
   }

   /** Listener for audio manager focus change */
   private static final class OnFocusChangeListener implements AudioManager.OnAudioFocusChangeListener {
      @Override
      public void onAudioFocusChange(int focusChange) {
         Log.w(TAG, "ONFOCUSCHANGE: " + focusChange);
         switch (focusChange) {
         case AudioManager.AUDIOFOCUS_GAIN:
            Log.w(TAG, "GAIN");
            methodChannel.invokeMethod("FOCUS_CHANGE", "AUDIOFOCUS_GAIN");
            break;
         case AudioManager.AUDIOFOCUS_LOSS:
            Log.w(TAG, "LOSS");
            methodChannel.invokeMethod("FOCUS_CHANGE", "AUDIOFOCUS_LOSS");
            break;
         case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
            Log.w(TAG, "ANOTHER LOSS");
            methodChannel.invokeMethod("FOCUS_CHANGE", "AUDIOFOCUS_LOSS_TRANSIENT");
            break;
         case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
            Log.w(TAG, "KAVO");
            methodChannel.invokeMethod("FOCUS_CHANGE", "AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK");
            break;
         }
      }
   }

   /** Check for if Intent action is VIEW */
   private boolean isIntentActionView() {
      Intent intent = getIntent();
      if (Intent.ACTION_VIEW.equals(intent.getAction())) {
         return true;
      }
      return false;
   }

   private List<String> retrieveSongs() {
      // Retrieve a list of Music files currently listed in the Media store DB via
      // URI.

      List<String> songs = new ArrayList<>();
      // Some audio may be explicitly marked as not being music
      String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0";

      String[] projection = { MediaStore.Audio.Media._ID, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DURATION };

      Cursor cursor = getApplicationContext().getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection, selection, null, "DATE_MODIFIED DESC");

      while (cursor.moveToNext()) {
         songs.add(new Song(cursor.getInt(0), cursor.getString(1), cursor.getString(2), getAlbumArt(cursor.getInt(3)),
               cursor.getString(4), cursor.getString(5), cursor.getInt(6)).toJson());
      }
      cursor.close();
      return songs;
   }

   private String getAlbumArt(int albumId) {
      Cursor cursor = getContentResolver().query(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
            new String[] { MediaStore.Audio.Albums._ID, MediaStore.Audio.Albums.ALBUM_ART },
            MediaStore.Audio.Albums._ID + "=?", new String[] { String.valueOf(albumId) }, null);

      String path = null;
      if (cursor.moveToFirst())
         path = cursor.getString(cursor.getColumnIndex(MediaStore.Audio.Albums.ALBUM_ART));
      cursor.close();
      return path;
   }

   /** Broadcast receiver for become noisy intent */
   private static class BecomingNoisyReceiver extends BroadcastReceiver {
      final EventChannel.EventSink eventSink;

      BecomingNoisyReceiver(EventChannel.EventSink eventSink) {
         super();
         this.eventSink = eventSink;
      }

      @Override
      public void onReceive(Context context, Intent intent) {
         if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
            eventSink.success("com.nt4f04uNd.player.BECAME_NOISY");
         }
      }
   }

   /** Broadcast receiver for notifications intents */
   private static class PlayerReceiver extends BroadcastReceiver {
      final EventChannel.EventSink eventSink;

      PlayerReceiver(EventChannel.EventSink eventSink) {
         super();
         this.eventSink = eventSink;
      }

      @Override
      public void onReceive(Context context, Intent intent) {
         Log.w(TAG, intent.getAction().toString());
         if (Constants.NOTIFICATION_INTENT_PLAY.equals(intent.getAction())) {
            eventSink.success(Constants.NOTIFICATION_INTENT_PLAY);
         } else if (Constants.NOTIFICATION_INTENT_PAUSE.equals(intent.getAction())) {
            eventSink.success(Constants.NOTIFICATION_INTENT_PAUSE);
         } else if (Constants.NOTIFICATION_INTENT_NEXT.equals(intent.getAction())) {
            eventSink.success(Constants.NOTIFICATION_INTENT_NEXT);
         } else if (Constants.NOTIFICATION_INTENT_PREV.equals(intent.getAction())) {
            eventSink.success(Constants.NOTIFICATION_INTENT_PREV);
         }
      }
   }

   private void createNotificationChannel() {
      // Create the NotificationChannel, but only on API 26+ because
      // the NotificationChannel class is new and not in the support library
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
         CharSequence name = "Управление музыкой";
         String description = "Канал уведомлений для управления фоновым воспроизведением музыки";
         int importance = NotificationManager.IMPORTANCE_LOW;
         NotificationChannel channel = new NotificationChannel(Constants.NOTIFICATION_CHANNEL_ID, name, importance);
         channel.setDescription(description);
         // Register the channel with the system; you can't change the importance
         // or other notification behaviors after this
         NotificationManager notificationManager = getSystemService(NotificationManager.class);
         notificationManager.createNotificationChannel(channel);
      }
   }

   private void initNotifications() {
      // Create notif. channel
      createNotificationChannel();

      // Init intent filters
      notificationIntentFilter.addAction(Constants.NOTIFICATION_INTENT_PLAY);
      notificationIntentFilter.addAction(Constants.NOTIFICATION_INTENT_PAUSE);
      notificationIntentFilter.addAction(Constants.NOTIFICATION_INTENT_PREV);
      notificationIntentFilter.addAction(Constants.NOTIFICATION_INTENT_NEXT);

      // Intent for switching to activity instead of opening a new one
      final Intent notificationIntent = new Intent(getApplicationContext(), MainActivity.class);
      notificationIntent.setAction(Intent.ACTION_MAIN);
      notificationIntent.addCategory(Intent.CATEGORY_LAUNCHER);
      notificationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      pendingNotificationIntent = PendingIntent.getActivity(getApplicationContext(), 0, notificationIntent, 0);

      // Init intents
      Intent playIntent = new Intent().setAction(Constants.NOTIFICATION_INTENT_PLAY);
      Intent pauseIntent = new Intent().setAction(Constants.NOTIFICATION_INTENT_PAUSE);
      Intent prevIntent = new Intent().setAction(Constants.NOTIFICATION_INTENT_PREV);
      Intent nextIntent = new Intent().setAction(Constants.NOTIFICATION_INTENT_NEXT);

      // Make them pending
      playPendingIntent = PendingIntent.getBroadcast(getApplicationContext(), 1, playIntent,
            PendingIntent.FLAG_UPDATE_CURRENT);
      pausePendingIntent = PendingIntent.getBroadcast(getApplicationContext(), 2, pauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT);
      prevPendingIntent = PendingIntent.getBroadcast(getApplicationContext(), 3, prevIntent,
            PendingIntent.FLAG_UPDATE_CURRENT);
      nextPendingIntent = PendingIntent.getBroadcast(getApplicationContext(), 4, nextIntent,
            PendingIntent.FLAG_UPDATE_CURRENT);

      notificationManager = NotificationManagerCompat.from(getApplicationContext());

   }

   private void buildNotification(String title, String artist, boolean isPlaying) {
      NotificationCompat.Builder builder = new NotificationCompat.Builder(getApplicationContext(),
            Constants.NOTIFICATION_CHANNEL_ID).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                  .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                  .setStyle(new androidx.media.app.NotificationCompat.MediaStyle())
                  .setSmallIcon(R.drawable.round_music_note_white_48).setOngoing(true) // Persistent setting
                  .setContentIntent(pendingNotificationIntent) // Set the intent that will fire when the user taps the
                                                               // notification
                  .setContentTitle(title).setContentText(artist)
                  .addAction(R.drawable.round_skip_previous_black_36, "Previous", prevPendingIntent)
                  .addAction(isPlaying ? R.drawable.round_pause_black_36 : R.drawable.round_play_arrow_black_36,
                        isPlaying ? "Pause" : "Play", isPlaying ? pausePendingIntent : playPendingIntent)
                  .addAction(R.drawable.round_skip_next_black_36, "Next", nextPendingIntent);

      // notificationId is a unique int for each notification that you must define
      notificationManager.notify(0, builder.build());
   }

   private void closeNotification() {
      notificationManager.cancel(0);
   }

   @Override
   protected void onCreate(Bundle savedInstanceState) {
      super.onCreate(savedInstanceState);
      GeneratedPluginRegistrant.registerWith(this);

      initNotifications();

      // Handers for media buttons
      // TODO: refactor
      audioSession = new MediaSession(getApplicationContext(), "TAG");
      audioSession.setCallback(new MediaSession.Callback() {
         @Override
         public boolean onMediaButtonEvent(final Intent mediaButtonIntent) {
            String intentAction = mediaButtonIntent.getAction();
            if (Intent.ACTION_MEDIA_BUTTON.equals(intentAction)) {
               KeyEvent event = mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
               if (event.getAction() == KeyEvent.ACTION_DOWN) {
                  switch (event.getKeyCode()) {
                  case KeyEvent.KEYCODE_HEADSETHOOK:
                     methodChannel.invokeMethod("HOOK_BUTTON_CLICK", null);
                  }
               }
            }
            return true;
         }
      });

      // Setup methodChannel
      methodChannel = new MethodChannel(getFlutterView(), methodChannelStream);
      // Setup event channel
      eventChannel = new EventChannel(getFlutterView(), eventChannelStream);

      // Audio manager become noisy event
      eventChannel.setStreamHandler(new EventChannel.StreamHandler() {
         @Override
         public void onListen(Object args, final EventChannel.EventSink events) {
            myPlayerReceiver = new PlayerReceiver(events);
            myNoisyAudioStreamReceiver = new BecomingNoisyReceiver(events);
            registerReceiver(myPlayerReceiver, notificationIntentFilter);
            registerReceiver(myNoisyAudioStreamReceiver, noisyIntentFilter);

            audioSession.setActive(true);
         }

         @Override
         public void onCancel(Object args) {
            unregisterReceiver(myPlayerReceiver);
            unregisterReceiver(myNoisyAudioStreamReceiver);
         }
      });

      // Audio manager focus
      if (audioManager == null)
         audioManager = (AudioManager) getApplicationContext().getSystemService(getApplicationContext().AUDIO_SERVICE);

      methodChannel.setMethodCallHandler(new MethodCallHandler() {
         @Override
         public void onMethodCall(MethodCall call, Result result) {
            // Note: this method is invoked on the main thread.
            switch (call.method) {
            case "REQUEST_FOCUS":
               result.success(requestFocus());
               break;
            case "ABANDON_FOCUS":
               result.success(abandonFocus());
               break;
            case "INTENT_ACTION_VIEW":
               result.success(isIntentActionView());
               break;
            case "RETRIEVE_SONGS":
               result.success(retrieveSongs());
               break;
            case "NOTIFICATION_SHOW":
               buildNotification(call.argument("title"), call.argument("artist"), call.argument("isPlaying"));
               result.success("");
               break;
            case "NOTIFICATION_CLOSE":
               closeNotification();
               result.success("");
               break;
            default:
               Log.w(TAG, "Invalid method name call from Dart code");
            }
         }
      });
   }

   // @Override
   // public boolean onKeyDown(int keyCode, KeyEvent event) {
   // switch (keyCode) {
   // All commented code is needed for handling media buttons

   // case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
   // Log.w(TAG, "1");

   // return true;
   // case KeyEvent.KEYCODE_MEDIA_NEXT:
   // Log.w(TAG, "2");

   // return true;
   // case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
   // Log.w(TAG, "3");

   // return true;
   // case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
   // Log.w(TAG, "4");

   // return true;
   // case KeyEvent.KEYCODE_MEDIA_REWIND:
   // Log.w(TAG, "5");

   // return true;
   // case KeyEvent.KEYCODE_MEDIA_STOP:
   // Log.w(TAG, "6");

   // return true;

   // Handling single headset button
   // case KeyEvent.KEYCODE_HEADSETHOOK:
   // methodChannel.invokeMethod("HOOK_BUTTON_CLICK", null);
   // return true;
   // }
   // return false;
   // }

   @Override
   protected void onDestroy() {
      super.onDestroy();
      unregisterReceiver(myNoisyAudioStreamReceiver);
      unregisterReceiver(myPlayerReceiver);
      closeNotification();
   }
}

// TODO: add support for headset and/or bluetooth buttons (check vk)
// TODO: probably? add luanch entent broadcast receiver and get extra agrument
// that denotes that activity has been opened from notification

// TODO: as I specify very big priority for mediabutton intent-filter, i should
// add handler to start music when media buttonm got clicked, but application is
// not started
// TODO: add broadreceiver that will handle hook click in background