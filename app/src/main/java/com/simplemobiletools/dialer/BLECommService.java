package com.simplemobiletools.dialer;

    import android.app.Notification;
    import android.app.NotificationChannel;
    import android.app.NotificationManager;
    import android.app.PendingIntent;
    import android.app.Service;
    import android.bluetooth.BluetoothAdapter;
    import android.bluetooth.BluetoothDevice;
    import android.bluetooth.BluetoothGatt;
    import android.bluetooth.BluetoothGattCharacteristic;
    import android.bluetooth.BluetoothGattDescriptor;
    import android.bluetooth.BluetoothGattServer;
    import android.bluetooth.BluetoothGattServerCallback;
    import android.bluetooth.BluetoothGattService;
    import android.bluetooth.BluetoothManager;
    import android.bluetooth.le.AdvertiseCallback;
    import android.bluetooth.le.AdvertiseData;
    import android.bluetooth.le.AdvertiseSettings;
    import android.bluetooth.le.BluetoothLeAdvertiser;
    import android.content.BroadcastReceiver;
    import android.content.Context;
    import android.content.Intent;
    import android.content.IntentFilter;
    import android.os.Build;
    import android.os.IBinder;
    import android.os.ParcelUuid;
    import android.util.Log;

    import androidx.annotation.Nullable;
    import androidx.core.app.NotificationCompat;

    import java.nio.charset.StandardCharsets;
    import java.util.Arrays;
    import java.util.HashSet;
    import java.util.UUID;

    import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;

    import com.simplemobiletools.dialer.activities.MainActivity;

public class BLECommService extends Service {
    public static final String CHANNEL_ID = "ForegroundServiceChannel";

    BroadcastReceiver answeredReceiver;
    BroadcastReceiver disconnectedReceiver;

    /**
     * Creates an IntentService.  Invoked by your subclass's constructor.
     *
     * @param name Used to name the worker thread, important only for debugging.
     */
    private static final String TAG = BLECommService.class.getCanonicalName();

    // Bluetooth
    private static final int REQUEST_ENABLE_BT = 1;
    private static final UUID CHARACTERISTIC_USER_DESCRIPTION_UUID = UUID
        .fromString("00002901-0000-1000-8000-00805f9b34fb");
    private static final UUID CLIENT_CHARACTERISTIC_CONFIGURATION_UUID = UUID
        .fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final UUID DIALER_SERVICE_UUID = UUID
        .fromString("00001234-0000-1000-8000-00805f9b34fb");

    private static final UUID DIAL_NUMBER_UUID = UUID
        .fromString("00000000-0000-1000-8000-00805f9b34fb");

    private static final UUID CALL_ANSWERED_UUID = UUID
        .fromString("00000006-0000-1000-8000-00805f9b34fb");

    private static final UUID KEEP_ALIVE_UUID = UUID
        .fromString("00000007-0000-1000-8000-00805f9b34fb");

    private BluetoothGattService mDialerService;
    private BluetoothGattCharacteristic mDialNumberControlPoint;
    private BluetoothGattCharacteristic mCallAnsweredControlPoint;
    private BluetoothGattCharacteristic mKeepAliveControlPoint;

    private HashSet<BluetoothDevice> mBluetoothDevices;
    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;
    private AdvertiseSettings mAdvSettings = new AdvertiseSettings.Builder()
        .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
        .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
        .setConnectable(true)
        .build();
    private AdvertiseData mAdvData = new AdvertiseData.Builder()
        .setIncludeTxPowerLevel(true)
        .addServiceUuid(new ParcelUuid(DIALER_SERVICE_UUID))
        .build();
    private AdvertiseData mAdvScanResponse = new AdvertiseData.Builder()
        .setIncludeDeviceName(true)
        .build();

    private BluetoothLeAdvertiser mAdvertiser;
    private final AdvertiseCallback mAdvCallback = new AdvertiseCallback() {
        public void onStartFailure(int errorCode) {
            super.onStartFailure(errorCode);
            Log.e(TAG, "Not broadcasting: " + errorCode);
        }
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            super.onStartSuccess(settingsInEffect);
            Log.v(TAG, "Broadcasting");
        }
    };

    private BluetoothGattServer mGattServer;
    private final BluetoothGattServerCallback mGattServerCallback = new BluetoothGattServerCallback() {
        @Override
        public void onConnectionStateChange(BluetoothDevice device, final int status, int newState) {
            super.onConnectionStateChange(device, status, newState);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothGatt.STATE_CONNECTED) {
                    mBluetoothDevices.add(device);
                    updateConnectedDevicesStatus();
                    Log.v(TAG, "Connected to device: " + device.getAddress());
                } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                    mBluetoothDevices.remove(device);
                    updateConnectedDevicesStatus();
                    Log.v(TAG, "Disconnected from device");
                }
            } else {
                mBluetoothDevices.remove(device);
                updateConnectedDevicesStatus();
                // There are too many gatt errors (some of them not even in the documentation) so we just
                // show the error to the user.
                Log.e(TAG, "Error when connecting: ");
            }
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset,
                                                BluetoothGattCharacteristic characteristic) {
            super.onCharacteristicReadRequest(device, requestId, offset, characteristic);
            Log.d(TAG, "Device tried to read characteristic: " + characteristic.getUuid());
            Log.d(TAG, "Value: " + Arrays.toString(characteristic.getValue()));
            if (offset != 0) {
                mGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset,
                    /* value (optional) */ null);
                return;
            }
            mGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS,
                offset, characteristic.getValue());
        }

        @Override
        public void onNotificationSent(BluetoothDevice device, int status) {
            super.onNotificationSent(device, status);
            Log.v(TAG, "Notification sent. Status: " + status);
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                                                 BluetoothGattCharacteristic characteristic, boolean preparedWrite, boolean responseNeeded,
                                                 int offset, byte[] value) {

            if (characteristic.getUuid() == DIAL_NUMBER_UUID) {
                String phoneNumber = new String(value, 0, value.length, StandardCharsets.UTF_8);
                Log.v(TAG, "Characteristic Write request: " + phoneNumber);

                Intent intent = new Intent();

                if (phoneNumber.equals("hangup")) {
                    intent.setAction("co.kwest.www.callmanager.hangup");
                } else {
                    intent.setAction("co.kwest.www.callmanager.dial");
                    intent.putExtra("dial", phoneNumber);
                }
                sendBroadcast(intent);
            }

            if (characteristic.getUuid() == KEEP_ALIVE_UUID) {
                Log.v(TAG, "Keepalive request: ");
            }


            if (true) {
                mGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS,
                    /* No need to respond with an offset */ 0,
                    /* No need to respond with a value */ null);
            }
        }

        @Override
        public void onDescriptorReadRequest(BluetoothDevice device, int requestId,
                                            int offset, BluetoothGattDescriptor descriptor) {
            super.onDescriptorReadRequest(device, requestId, offset, descriptor);
            Log.d(TAG, "Device tried to read descriptor: " + descriptor.getUuid());
            Log.d(TAG, "Value: " + Arrays.toString(descriptor.getValue()));
            if (offset != 0) {
                mGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset,
                    /* value (optional) */ null);
                return;
            }
            mGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                descriptor.getValue());
        }

        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
                                             BluetoothGattDescriptor descriptor, boolean preparedWrite, boolean responseNeeded,
                                             int offset,
                                             byte[] value) {
            super.onDescriptorWriteRequest(device, requestId, descriptor, preparedWrite, responseNeeded,
                offset, value);
            Log.v(TAG, "Descriptor Write Request " + descriptor.getUuid() + " " + Arrays.toString(value));
            int status;
            if (descriptor.getUuid() == CLIENT_CHARACTERISTIC_CONFIGURATION_UUID) {
                BluetoothGattCharacteristic characteristic = descriptor.getCharacteristic();
                boolean supportsNotifications = (characteristic.getProperties() &
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;
                boolean supportsIndications = (characteristic.getProperties() &
                    BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;

                if (!(supportsNotifications || supportsIndications)) {
                    status = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED;
                } else if (value.length != 2) {
                    status = BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH;
                } else if (Arrays.equals(value, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)) {
                    status = BluetoothGatt.GATT_SUCCESS;
                    //mCurrentServiceFragment.notificationsDisabled(characteristic);
                    descriptor.setValue(value);
                } else if (supportsNotifications &&
                    Arrays.equals(value, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    status = BluetoothGatt.GATT_SUCCESS;
                    //mCurrentServiceFragment.notificationsEnabled(characteristic, false /* indicate */);
                    descriptor.setValue(value);
                } else if (supportsIndications &&
                    Arrays.equals(value, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)) {
                    status = BluetoothGatt.GATT_SUCCESS;
                    //mCurrentServiceFragment.notificationsEnabled(characteristic, true /* indicate */);
                    descriptor.setValue(value);
                } else {
                    status = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED;
                }
            } else {
                status = BluetoothGatt.GATT_SUCCESS;
                descriptor.setValue(value);
            }
            if (responseNeeded) {
                mGattServer.sendResponse(device, requestId, status,
                    /* No need to respond with offset */ 0,
                    /* No need to respond with a value */ null);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        final IntentFilter answeredFilter = new IntentFilter();
        //adding some filters
        answeredFilter.addAction("co.kwest.www.callmanager.answered");
        this.answeredReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // Signal the call has been answered
                notifyAnswered();
            }
        };
        registerReceiver(answeredReceiver, answeredFilter);

        final IntentFilter disconnectedFilter = new IntentFilter();
        disconnectedFilter.addAction("co.kwest.www.callmanager.disconnected");
        this.disconnectedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // Signal the call has been disconnected
                notifyDisconnected();
            }
        };
        registerReceiver(disconnectedReceiver, disconnectedFilter);
    }
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this,
            0, notificationIntent, 0);
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Bluetooth Dialer Service")
            .setContentText("Support service for Kwest CATI")
            .setContentIntent(pendingIntent)
            .build();
        startForeground(1, notification);

        startup();

        //do heavy work on a background thread
        //stopSelf();
        return START_STICKY;
    }
    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterReceiver(answeredReceiver);
        unregisterReceiver(disconnectedReceiver);
        disconnectFromDevices();
    }
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                CHANNEL_ID,
                "Foreground Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(serviceChannel);
        }
    }

    protected void startup() {
        mBluetoothDevices = new HashSet<>();
        mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        mBluetoothAdapter = mBluetoothManager.getAdapter();

        // If the user disabled Bluetooth when the app was in the background,
        // openGattServer() will return null.
        mGattServer = mBluetoothManager.openGattServer(this, mGattServerCallback);
        if (mGattServer == null) {
            ensureBleFeaturesAvailable();
            return;
        }
        // Add a service for a total of three services (Generic Attribute and Generic Access
        // are present by default).
        startBluetoothService();

        mGattServer.addService(mDialerService);

        if (mBluetoothAdapter.isMultipleAdvertisementSupported()) {
            mAdvertiser = mBluetoothAdapter.getBluetoothLeAdvertiser();
            mAdvertiser.startAdvertising(mAdvSettings, mAdvData, mAdvScanResponse, mAdvCallback);
        } else {
        }

    }

    public void startBluetoothService() {

        mDialNumberControlPoint =
            new BluetoothGattCharacteristic(DIAL_NUMBER_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);

        mCallAnsweredControlPoint =
            new BluetoothGattCharacteristic(CALL_ANSWERED_UUID,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                0);

        mKeepAliveControlPoint =
            new BluetoothGattCharacteristic(KEEP_ALIVE_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);

        mCallAnsweredControlPoint.addDescriptor(getClientCharacteristicConfigurationDescriptor());
        mCallAnsweredControlPoint.addDescriptor(getCharacteristicUserDescriptionDescriptor("Answered"));

        mDialerService = new BluetoothGattService(DIALER_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        mDialerService.addCharacteristic(mDialNumberControlPoint);
        mDialerService.addCharacteristic(mCallAnsweredControlPoint);
        //mDialerService.addCharacteristic(mKeepAliveControlPoint);
    }

    ///////////////////////
    ////// Bluetooth //////
    ///////////////////////
    public static BluetoothGattDescriptor getClientCharacteristicConfigurationDescriptor() {
        BluetoothGattDescriptor descriptor = new BluetoothGattDescriptor(
            CLIENT_CHARACTERISTIC_CONFIGURATION_UUID,
            (BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE));
        descriptor.setValue(new byte[]{0, 0});
        return descriptor;
    }

    public static BluetoothGattDescriptor getCharacteristicUserDescriptionDescriptor(String defaultValue) {
        BluetoothGattDescriptor descriptor = new BluetoothGattDescriptor(
            CHARACTERISTIC_USER_DESCRIPTION_UUID,
            (BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE));
        try {
            descriptor.setValue(defaultValue.getBytes(StandardCharsets.UTF_8));
        } finally {
            return descriptor;
        }
    }

    public void notifyAnswered() {

    }

    public void notifyDisconnected() {
        mCallAnsweredControlPoint.setValue("disconnected");
        for (BluetoothDevice device : mBluetoothDevices) {
            // true for indication (acknowledge) and false for notification (unacknowledge).
            boolean rc = mGattServer.notifyCharacteristicChanged(device, mCallAnsweredControlPoint, false);

        }
    }

    private void ensureBleFeaturesAvailable() {
        if (mBluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth not supported");
        } else if (!mBluetoothAdapter.isEnabled()) {
            // Make sure bluetooth is enabled.
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            enableBtIntent.setFlags(FLAG_ACTIVITY_NEW_TASK);
            startActivity(enableBtIntent);
        }
    }
    private void disconnectFromDevices() {
        Log.d(TAG, "Disconnecting devices...");
        for (BluetoothDevice device : mBluetoothManager.getConnectedDevices(
            BluetoothGattServer.GATT)) {
            Log.d(TAG, "Devices: " + device.getAddress() + " " + device.getName());
            mGattServer.cancelConnection(device);
        }
        mGattServer.close();
    }

    private void updateConnectedDevicesStatus() {
    }}
