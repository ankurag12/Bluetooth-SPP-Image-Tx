package ru.sash0k.bluetooth_terminal.activity;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.text.InputFilter;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;

import com.actionbarsherlock.app.ActionBar;
import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuItem;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;

import ru.sash0k.bluetooth_terminal.DeviceData;
import ru.sash0k.bluetooth_terminal.R;
import ru.sash0k.bluetooth_terminal.Utils;
import ru.sash0k.bluetooth_terminal.bluetooth.DeviceConnector;
import ru.sash0k.bluetooth_terminal.bluetooth.DeviceListActivity;

public final class DeviceControlActivity extends BaseActivity {
    private static final String DEVICE_NAME = "DEVICE_NAME";
    private static final String LOG = "LOG";

    private static final SimpleDateFormat timeformat = new SimpleDateFormat("HH:mm:ss.SSS");

    private static String MSG_NOT_CONNECTED;
    private static String MSG_CONNECTING;
    private static String MSG_CONNECTED;

    private static byte fileData[];

    private static int fileTotalSize = 0;
    private static int fileBytesStart = 0;
    private static int fileBytesEnd = 0;

    private static int commSizeInBytes = 250;

    private static String ack = "1";

    private enum dataPacketType
    {
        CMD_PREP_FILE_TRANSFER,
        DATA_FILE_CHUNK,
        CMD_END_OF_FILE,
        CMD_UNMOUNT_SDCARD,
        CMD_DEFAULT

    }

    private static dataPacketType currentDataPacketType;
    private static dataPacketType nextDataPacketType;

    private static DeviceConnector connector;
    private static BluetoothResponseHandler mHandler;

    private TextView logTextView;
    private EditText commandEditText;

    // Настройки приложения
    private boolean hexMode, needClean;
    private boolean show_timings, show_direction;
    private String command_ending;
    private String deviceName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PreferenceManager.setDefaultValues(this, R.xml.settings_activity, false);

        if (mHandler == null) mHandler = new BluetoothResponseHandler(this);
        else mHandler.setTarget(this);

        MSG_NOT_CONNECTED = getString(R.string.msg_not_connected);
        MSG_CONNECTING = getString(R.string.msg_connecting);
        MSG_CONNECTED = getString(R.string.msg_connected);

        setContentView(R.layout.activity_terminal);
        if (isConnected() && (savedInstanceState != null)) {
            setDeviceName(savedInstanceState.getString(DEVICE_NAME));
        } else getSupportActionBar().setSubtitle(MSG_NOT_CONNECTED);


        this.logTextView = (TextView) findViewById(R.id.log_textview);
        this.logTextView.setMovementMethod(new ScrollingMovementMethod());
        if (savedInstanceState != null)
            logTextView.setText(savedInstanceState.getString(LOG));

        this.commandEditText = (EditText) findViewById(R.id.command_edittext);
        // soft-keyboard send button
        this.commandEditText.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    sendCommand(null);
                    return true;
                }
                return false;
            }
        });
        // hardware Enter button
        this.commandEditText.setOnKeyListener(new View.OnKeyListener() {
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    switch (keyCode) {
                        case KeyEvent.KEYCODE_ENTER:
                            sendCommand(null);
                            return true;
                        default:
                            break;
                    }
                }
                return false;
            }
        });
    }
    // ==========================================================================

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(DEVICE_NAME, deviceName);
        if (logTextView != null) {
            final String log = logTextView.getText().toString();
            outState.putString(LOG, log);
        }
    }
    // ============================================================================


    /**
     * Проверка готовности соединения
     */
    private boolean isConnected() {
        return (connector != null) && (connector.getState() == DeviceConnector.STATE_CONNECTED);
    }
    // ==========================================================================


    /**
     * Разорвать соединение
     */
    private void stopConnection() {
        if (connector != null) {
            connector.stop();
            connector = null;
            deviceName = null;
        }
    }
    // ==========================================================================


    /**
     * Список устройств для подключения
     */
    private void startDeviceListActivity() {
        stopConnection();
        Intent serverIntent = new Intent(this, DeviceListActivity.class);
        startActivityForResult(serverIntent, REQUEST_CONNECT_DEVICE);
    }
    // ============================================================================


    /**
     * Обработка аппаратной кнопки "Поиск"
     *
     * @return
     */
    @Override
    public boolean onSearchRequested() {
        if (super.isAdapterReady()) startDeviceListActivity();
        return false;
    }
    // ==========================================================================


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getSupportMenuInflater().inflate(R.menu.device_control_activity, menu);
        return true;
    }
    // ============================================================================


    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {

            case R.id.menu_search:
                if (super.isAdapterReady()) {
                    if (isConnected()) stopConnection();
                    else startDeviceListActivity();
                } else {
                    Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                    startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
                }
                return true;

            case R.id.menu_clear:
                if (logTextView != null) logTextView.setText("");
                return true;

            case R.id.menu_send:
                if (logTextView != null) {
                    final String msg = logTextView.getText().toString();
                    final Intent intent = new Intent(Intent.ACTION_SEND);
                    intent.setType("text/plain");
                    intent.putExtra(Intent.EXTRA_TEXT, msg);
                    startActivity(Intent.createChooser(intent, getString(R.string.menu_send)));
                }
                return true;

            case R.id.menu_settings:
                final Intent intent = new Intent(this, SettingsActivity.class);
                startActivity(intent);
                return true;

            default:
                return super.onOptionsItemSelected(item);
        }
    }
    // ============================================================================


    @Override
    public void onStart() {
        super.onStart();

        // hex mode
        final String mode = Utils.getPrefence(this, getString(R.string.pref_commands_mode));
        this.hexMode = mode.equals("HEX");
        if (hexMode) {
            commandEditText.setInputType(InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
            commandEditText.setFilters(new InputFilter[]{new Utils.InputFilterHex()});
        } else {
            commandEditText.setInputType(InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            commandEditText.setFilters(new InputFilter[]{});
        }

        // Окончание строки
        this.command_ending = getCommandEnding();

        // Формат отображения лога команд
        this.show_timings = Utils.getBooleanPrefence(this, getString(R.string.pref_log_timing));
        this.show_direction = Utils.getBooleanPrefence(this, getString(R.string.pref_log_direction));
        this.needClean = Utils.getBooleanPrefence(this, getString(R.string.pref_need_clean));
    }
    // ============================================================================


    /**
     * Получить из настроек признак окончания команды
     */
    private String getCommandEnding() {
        String result = Utils.getPrefence(this, getString(R.string.pref_commands_ending));
        if (result.equals("\\r\\n")) result = "\r\n";
        else if (result.equals("\\n")) result = "\n";
        else if (result.equals("\\r")) result = "\r";
        else result = "";
        return result;
    }
    // ============================================================================
    public void getData(View view) {
        Intent mediaIntent = new Intent(Intent.ACTION_GET_CONTENT);
        mediaIntent.setType("*/*"); //set mime type as per requirement
        startActivityForResult(mediaIntent, REQUEST_FILE);


    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        switch (requestCode) {
            case REQUEST_CONNECT_DEVICE:
                // When DeviceListActivity returns with a device to connect
                if (resultCode == Activity.RESULT_OK) {
                    String address = data.getStringExtra(DeviceListActivity.EXTRA_DEVICE_ADDRESS);
                    BluetoothDevice device = btAdapter.getRemoteDevice(address);
                    if (super.isAdapterReady() && (connector == null)) setupConnector(device);
                }
                break;
            case REQUEST_ENABLE_BT:
                // When the request to enable Bluetooth returns
                super.pendingRequestEnableBt = false;
                if (resultCode != Activity.RESULT_OK) {
                    Utils.log("BT not enabled");
                }
                break;
            case REQUEST_FILE: {
                Utils.log("Requesting File from SDcard! status " + resultCode);
                if (resultCode == Activity.RESULT_OK) {
                    Uri fileUri = data.getData();

                    //Get the binary file
                    File file = new File(fileUri.getPath());

                    fileTotalSize = (int)file.length();
                    fileBytesStart = 0;

                    Utils.log("sdcard: " + fileUri + " total size: " + fileTotalSize);

                    //Read bytes from file
                    fileData = new byte[fileTotalSize];

                    try {
                        InputStream in = new BufferedInputStream(new FileInputStream(file));
                        in.read(fileData);
                        in.close();
                    } catch (IOException e) {
                        //You'll need to add proper error handling here
                    }

                    //Find the view by its id
//                    TextView tv = (TextView) findViewById(R.id.loadSD);

                    //Set the text
//                    tv.setText(file_text);
                    currentDataPacketType = dataPacketType.CMD_PREP_FILE_TRANSFER;
                    nextDataPacketType = dataPacketType.CMD_PREP_FILE_TRANSFER;
                    sendBtDataPacket();
                }

                break;
            }
        }
    }
    // ==========================================================================


    /**
     * Установка соединения с устройством
     */
    private void setupConnector(BluetoothDevice connectedDevice) {
        stopConnection();
        try {
            String emptyName = getString(R.string.empty_device_name);
            DeviceData data = new DeviceData(connectedDevice, emptyName);
            connector = new DeviceConnector(data, mHandler);
            connector.connect();
        } catch (IllegalArgumentException e) {
            Utils.log("setupConnector failed: " + e.getMessage());
        }
    }
    // ==========================================================================


    /**
     * Отправка команды устройству
     */
    public void sendCommand(View view) {
        if (commandEditText != null) {
            String commandString = commandEditText.getText().toString();
            if (commandString.isEmpty()) return;

            // Дополнение команд в hex
            if (hexMode && (commandString.length() % 2 == 1)) {
                commandString = "0" + commandString;
                commandEditText.setText(commandString);
            }
            byte[] command = (hexMode ? Utils.toHex(commandString) : commandString.getBytes());
            if (command_ending != null) command = Utils.concat(command, command_ending.getBytes());
            if (isConnected()) {
                connector.write(command);
                appendLog(commandString, hexMode, true, needClean);
            }
        }
    }
    // ==========================================================================


    /**
     * Добавление ответа в лог
     *
     * @param message  - текст для отображения
     * @param outgoing - направление передачи
     */
    void appendLog(String message, boolean hexMode, boolean outgoing, boolean clean) {

        StringBuilder msg = new StringBuilder();
        if (show_timings) msg.append("[").append(timeformat.format(new Date())).append("]");
        if (show_direction) {
            final String arrow = (outgoing ? " << " : " >> ");
            msg.append(arrow);
        } else msg.append(" ");

        msg.append(hexMode ? Utils.printHex(message) : message);
        if (outgoing) msg.append('\n');
        logTextView.append(msg);

        final int scrollAmount = logTextView.getLayout().getLineTop(logTextView.getLineCount()) - logTextView.getHeight();
        if (scrollAmount > 0)
            logTextView.scrollTo(0, scrollAmount);
        else logTextView.scrollTo(0, 0);

        if (clean) commandEditText.setText("");
    }
    // =========================================================================

    public void sendBtDataPacket() {
        byte id;
        short length;
        byte [] payload;
        byte trailer;
        byte[] dataPacketBytes;

        switch(currentDataPacketType) {

            case CMD_PREP_FILE_TRANSFER:
                Utils.log("Prep for file transfer");
                id = (byte) dataPacketType.CMD_PREP_FILE_TRANSFER.ordinal();
                payload = new byte[]{0};
                length = (short) payload.length;
                trailer = '\n';
                nextDataPacketType = dataPacketType.DATA_FILE_CHUNK;
                break;

            case DATA_FILE_CHUNK:
                id = (byte) dataPacketType.DATA_FILE_CHUNK.ordinal();
                nextDataPacketType = dataPacketType.DATA_FILE_CHUNK;

                fileBytesEnd = fileBytesStart + commSizeInBytes;
                if(fileBytesEnd >= fileTotalSize) {
                    fileBytesEnd = fileTotalSize;
                    Utils.log("Sending last chunk of data");
                    nextDataPacketType = dataPacketType.CMD_END_OF_FILE;
                }
                Utils.log("Sending bytes: "+ fileBytesStart + " to bytes "+ fileBytesEnd);
                payload = Arrays.copyOfRange(fileData, fileBytesStart, fileBytesEnd);
                length = (short) payload.length;
                trailer = '\n';
                fileBytesStart = fileBytesEnd;
                break;

            case CMD_END_OF_FILE:
                Utils.log("End of File");
                id = (byte) dataPacketType.CMD_END_OF_FILE.ordinal();
                payload = new byte[]{0};
                length = (short) payload.length;
                trailer = '\n';
                nextDataPacketType = dataPacketType.CMD_DEFAULT;
                break;

            case CMD_UNMOUNT_SDCARD:
                id = (byte) dataPacketType.CMD_UNMOUNT_SDCARD.ordinal();
                payload = new byte[]{0};
                length = (short) payload.length;
                trailer = '\n';
                break;

            default:
                id = -1;
                payload = new byte[]{0};
                length = (short) payload.length;
                trailer = '\n';
        }

        DataPacket dataPacket = new DataPacket(id, length, payload, trailer );

        dataPacketBytes = dataPacket.dataPacketToByteArray(dataPacket);
        Utils.log("Length of dataPacketBytes: " + dataPacketBytes.length);
        if (isConnected()) {
            connector.write(dataPacketBytes);
            //String logStr = new String(dataPacketBytes, StandardCharsets.UTF_8);
            //appendLog(logStr, hexMode, true, needClean);
        }

/*        try {
            //dataPacketBytes = serialize(dataPacket);
            dataPacketBytes = dataPacket.dataPacketToByteArray(dataPacket);
            Utils.log("Length of dataPacketBytes: " + dataPacketBytes.length);
            if (isConnected()) {
                connector.write(dataPacketBytes);
                String logStr = new String(dataPacketBytes, StandardCharsets.UTF_8);
                appendLog(logStr, hexMode, true, needClean);
            }
        } catch (IOException e) {
            Utils.log("IO Exception in serialize");
            e.printStackTrace();
        }*/


    }


    void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
        getSupportActionBar().setSubtitle(deviceName);
    }
    // ==========================================================================

    /**
     * Обработчик приёма данных от bluetooth-потока
     */
    private static class BluetoothResponseHandler extends Handler {
        private WeakReference<DeviceControlActivity> mActivity;

        public BluetoothResponseHandler(DeviceControlActivity activity) {
            mActivity = new WeakReference<DeviceControlActivity>(activity);
        }

        public void setTarget(DeviceControlActivity target) {
            mActivity.clear();
            mActivity = new WeakReference<DeviceControlActivity>(target);
        }

        @Override
        public void handleMessage(Message msg) {
            DeviceControlActivity activity = mActivity.get();
            if (activity != null) {
                switch (msg.what) {
                    case MESSAGE_STATE_CHANGE:

                        Utils.log("MESSAGE_STATE_CHANGE: " + msg.arg1);
                        final ActionBar bar = activity.getSupportActionBar();
                        switch (msg.arg1) {
                            case DeviceConnector.STATE_CONNECTED:
                                bar.setSubtitle(MSG_CONNECTED);
                                break;
                            case DeviceConnector.STATE_CONNECTING:
                                bar.setSubtitle(MSG_CONNECTING);
                                break;
                            case DeviceConnector.STATE_NONE:
                                bar.setSubtitle(MSG_NOT_CONNECTED);
                                break;
                        }
                        break;

                    case MESSAGE_READ:

                        final String readMessage = (String) msg.obj;
                        Utils.log("incoming msg: " + readMessage);

                        if(readMessage.contains(ack)) {
                            Utils.log("ack rx: ");
                            currentDataPacketType = nextDataPacketType;
                            activity.sendBtDataPacket();
                        }
                        else {
                            Utils.log("Invalid ACK, Resending Data Packet ");
                            activity.sendBtDataPacket();
                        }


                        if (readMessage != null) {
                            activity.appendLog(readMessage, false, false, activity.needClean);
                        }
                        break;

                    case MESSAGE_DEVICE_NAME:
                        activity.setDeviceName((String) msg.obj);
                        break;

                    case MESSAGE_WRITE:
                        // stub
                        break;

                    case MESSAGE_TOAST:
                        // stub
                        break;
                }
            }
        }
    }
    // ==========================================================================

    private static class DataPacket implements Serializable {

        private byte dataPacketID;
        private short dataPacketLength;
        private byte[] dataPacketPayload;
        private byte dataPacketTrailer;


        public DataPacket(byte id, short length, byte[] payload, byte trailer) {
            dataPacketID = id;
            dataPacketLength = length;
            dataPacketPayload = payload;
            dataPacketTrailer = trailer;
        }

        // TODO: Not a good code design with all the hard coding but Serialization doesn't work as in C
        // Need to come up with a better way
        public byte[] dataPacketToByteArray(DataPacket dataPacket) {
            byte[] dataPacketBytes = new byte[dataPacket.dataPacketPayload.length+4];
            dataPacketBytes[0] = dataPacket.dataPacketID;
            dataPacketBytes[1] = (byte) (dataPacket.dataPacketLength>>8*0 & 0xFF);
            dataPacketBytes[2] = (byte) (dataPacket.dataPacketLength>>8*1 & 0xFF);
            System.arraycopy(dataPacket.dataPacketPayload,0,dataPacketBytes,3,dataPacket.dataPacketPayload.length);
            dataPacketBytes[dataPacketBytes.length-1] = dataPacket.dataPacketTrailer;
            return dataPacketBytes;
        }

    }

//    private static byte[] serialize(Object obj) throws IOException {
//        ByteArrayOutputStream out = new ByteArrayOutputStream();
//        ObjectOutputStream os = new ObjectOutputStream(out);
//        os.writeObject(obj);
//        os.flush();
//        Utils.log("byte array: " + out.toString());
//        return out.toByteArray();
//    }


}