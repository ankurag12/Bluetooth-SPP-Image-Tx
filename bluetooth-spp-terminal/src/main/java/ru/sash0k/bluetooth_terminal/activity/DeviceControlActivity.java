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
import java.lang.reflect.Field;
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

    private static String fileNameExt;

    private static int fileTotalSize = 0;
    private static int fileBytesStart = 0;
    private static int fileBytesEnd = 0;

    private static int maxPayloadSizeBytes = 786;   // Must be a multiple of 2

    private static String ack = "1";

    private enum dataPacketType
    {
        CMD_PREP_FILE_TRANSFER,
        PNM_FILE_HEADER,
        DATA_FILE_CHUNK,
        CMD_END_OF_FILE,
        CMD_UNMOUNT_SDCARD,
        CMD_DISPLAY_IMAGE,
        CMD_DEFAULT

    }

    private enum dataPacketCompression
    {
        NO_COMPRESSION,
        TWO_BYTES_TO_ONE_COMPRESSION
    }

    private enum pnmType
    {
        PNM_BITMAP,
        PNM_GREYSCALE
    }

    private enum fileIDs {
        ECODE_FILE_ID (1),
        VCOM_FILE_ID (2),
        WAVEFORM_FILE_ID (3),
        HWINFO_FILE_ID (4),
        REG_OVERRIDE_FILE_ID (5),
        RECEIVED_IMG_FILE_ID (6);

        private int value;

        private fileIDs(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
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

                    int i = fileUri.toString().lastIndexOf('.');
                    if(i>=0)
                        fileNameExt = fileUri.toString().substring(i + 1);

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

    public PNMheaderParams pnmReadHeader(int paramCnt) {
        char ch;
        int digits = 0;
        boolean found = false;
        boolean inComment = false;
        int bytePtr=2;
        int val=0;
        int[] pnmParams = new int[paramCnt];

        for (int j=0; j<paramCnt; j++) {
            while (!found) {
                ch = (char) fileData[bytePtr++];
                if (bytePtr > maxPayloadSizeBytes) {
                    Utils.log("File header size must be less than " + maxPayloadSizeBytes + " bytes in current implementation");
                    break;
                }
                switch (ch) {
                    case '#':
                        inComment = true;
                        break;
                    case ' ':
                    case '\t':
                    case '\r':
                    case '\n':
                        if (!inComment && digits > 0)
                            found = true;
                        if (ch == '\r' || ch == '\n')
                            inComment = false;
                        break;
                    case '0':
                    case '1':
                    case '2':
                    case '3':
                    case '4':
                    case '5':
                    case '6':
                    case '7':
                    case '8':
                    case '9':
                        if (!inComment) {
                            val = val * 10 + (ch - '0');
                        }
                        digits++;
                        break;
                    default:
                        break;
                }
            }
            pnmParams[j] = val;
            found=false;
            inComment = false;
            digits= 0;
            val=0;
        }
        Utils.log(pnmParams[0] + " " + pnmParams[1] + " " + pnmParams[2] + " " + bytePtr);
        return new PNMheaderParams(pnmParams[0],pnmParams[1], pnmParams[2],bytePtr);

    }

    // =========================================================================

    public void sendBtDataPacket() {
        byte id;
        byte comp;
        short length;
        byte [] payload_tmp;
        byte [] payload;
        byte trailer;
        byte[] dataPacketBytes;

        switch(currentDataPacketType) {

            case CMD_PREP_FILE_TRANSFER:
                Utils.log("Prep for file transfer");
                id = (byte) dataPacketType.CMD_PREP_FILE_TRANSFER.ordinal();
                comp = (byte) dataPacketCompression.NO_COMPRESSION.ordinal();
                DisplayCoordinates displayCoordinates = new DisplayCoordinates(200,200,1280-400,960-400,200,200);       // These values have to come from numeric input/PGM header/cropping tool
                payload = displayCoordinates.dispCoordToByteArray(displayCoordinates);
                length = (short) payload.length;
                Utils.log(" payload length = " + length);
                trailer = '\n';
                if(fileNameExt.toUpperCase().equals("PGM") || fileNameExt.toUpperCase().equals("PBM"))
                    nextDataPacketType = dataPacketType.PNM_FILE_HEADER;
                else
                    nextDataPacketType = dataPacketType.DATA_FILE_CHUNK;
                break;

            case PNM_FILE_HEADER:
                id = (byte) dataPacketType.PNM_FILE_HEADER.ordinal();
                comp = (byte) dataPacketCompression.NO_COMPRESSION.ordinal();
                if(!(fileData[0]=='P' && (fileData[1] == '4' || fileData[1] == '5')))
                    Utils.log("PNM file format error");
                int numParams = 3;
                fileBytesEnd = fileBytesStart + pnmReadHeader(numParams).getHeaderSize();
                payload = Arrays.copyOfRange(fileData, fileBytesStart, fileBytesEnd);
                length = (short) payload.length;
                trailer = '\n';
                nextDataPacketType = dataPacketType.DATA_FILE_CHUNK;
                fileBytesStart = fileBytesEnd;
                break;

            case DATA_FILE_CHUNK:
                id = (byte) dataPacketType.DATA_FILE_CHUNK.ordinal();
                comp = (byte) dataPacketCompression.TWO_BYTES_TO_ONE_COMPRESSION.ordinal();
                nextDataPacketType = dataPacketType.DATA_FILE_CHUNK;
                fileBytesEnd = fileBytesStart + maxPayloadSizeBytes*2;

                if(fileBytesEnd >= fileTotalSize) {
                    fileBytesEnd = fileTotalSize;
                    Utils.log("Sending last chunk of data");
                    nextDataPacketType = dataPacketType.CMD_END_OF_FILE;
                }
                payload_tmp = Arrays.copyOfRange(fileData, fileBytesStart, fileBytesEnd);
                payload=new byte[payload_tmp.length/2];
                for(int i=0;i<payload.length;i++) {
                    payload[i] = (byte)((payload_tmp[2*i] & 0xF0)|(payload_tmp[2*i+1]>>4));
                }

                Utils.log("Sending bytes: "+ fileBytesStart + " to bytes "+ fileBytesEnd);
                length = (short) payload.length;
                trailer = '\n';
                fileBytesStart = fileBytesEnd;
                break;

            case CMD_END_OF_FILE:
                Utils.log("End of File");
                id = (byte) dataPacketType.CMD_END_OF_FILE.ordinal();
                comp = (byte) dataPacketCompression.NO_COMPRESSION.ordinal();
                payload = new byte[]{};
                length = (short) payload.length;
                trailer = '\n';
                nextDataPacketType = dataPacketType.CMD_DISPLAY_IMAGE;
                break;

            case CMD_UNMOUNT_SDCARD:
                id = (byte) dataPacketType.CMD_UNMOUNT_SDCARD.ordinal();
                comp = (byte) dataPacketCompression.NO_COMPRESSION.ordinal();
                payload = new byte[]{0};
                length = (short) payload.length;
                trailer = '\n';
                nextDataPacketType = dataPacketType.CMD_DEFAULT;
                break;

            case CMD_DISPLAY_IMAGE:
                id = (byte) dataPacketType.CMD_DISPLAY_IMAGE.ordinal();
                comp = (byte) dataPacketCompression.NO_COMPRESSION.ordinal();
                payload = new byte[]{(byte)fileIDs.RECEIVED_IMG_FILE_ID.getValue()};
                length = (short) payload.length;
                trailer = '\n';
                nextDataPacketType = dataPacketType.CMD_DEFAULT;
                break;

            default:
                id = -1;
                comp = (byte) dataPacketCompression.NO_COMPRESSION.ordinal();
                payload = new byte[]{6};
                length = (short) payload.length;
                trailer = '\n';
        }

        DataPacket dataPacket = new DataPacket(id, comp, length, payload, trailer );

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
                        //Utils.log("incoming msg: " + readMessage);

                        if(readMessage.contains(ack)) {
                            Utils.log("ack rx: ");
                            currentDataPacketType = nextDataPacketType;
                            activity.sendBtDataPacket();
                        }
                        else {
                            Utils.log("Invalid ACK, must resend Data Packet ");
                            //activity.sendBtDataPacket();
                        }


                        if (readMessage != null) {
                            //activity.appendLog(readMessage, false, false, activity.needClean);
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
        private byte dataPacketComp;
        private short dataPacketLength;
        private byte[] dataPacketPayload;
        private byte dataPacketTrailer;


        public DataPacket(byte id, byte comp, short length, byte[] payload, byte trailer) {
            dataPacketID = id;
            dataPacketComp = comp;
            dataPacketLength = length;
            dataPacketPayload = payload;
            dataPacketTrailer = trailer;
        }

        // TODO: Not a good code design with all the hard coding but Serialization doesn't work as in C
        // Need to come up with a better way
        public byte[] dataPacketToByteArray(DataPacket dataPacket) {
            byte[] dataPacketBytes = new byte[dataPacket.dataPacketPayload.length+5];
            dataPacketBytes[0] = dataPacket.dataPacketID;
            dataPacketBytes[1] = dataPacket.dataPacketComp;
            dataPacketBytes[2] = (byte) (dataPacket.dataPacketLength>>8*0 & 0xFF);
            dataPacketBytes[3] = (byte) (dataPacket.dataPacketLength>>8*1 & 0xFF);
            System.arraycopy(dataPacket.dataPacketPayload,0,dataPacketBytes,4,dataPacket.dataPacketPayload.length);
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

    private static class PNMheaderParams{
        private int imgWidth;
        private int imgHeight;
        private int maxColorScale;
        private int headerSizeBytes;

        public PNMheaderParams(int width, int height, int colorScale, int size) {
            imgWidth = width;
            imgHeight = height;
            maxColorScale = colorScale;
            headerSizeBytes = size;
        }

        public int getHeaderSize(){
            return headerSizeBytes;
        }
    }

    private static class DisplayCoordinates {
        private int leftOut;
        private int topOut;
        private int widthOut;
        private int heightOut;
        private int leftIn;
        private int topIn;

        private int getFieldIndex(String string){
            String fields[] = new String[]{"leftOut", "topOut", "widthOut", "heightOut", "leftIn", "topIn"};    // This sequence should match what is expected at the receiving end
            return Arrays.asList(fields).indexOf(string);
        }

        public DisplayCoordinates(int areaLeft, int areaTop, int areaWidth, int areaHeight, int imgLeft, int imgTop) {
            leftOut = areaLeft;
            topOut = areaTop;
            widthOut = areaWidth;
            heightOut = areaHeight;
            leftIn = imgLeft;
            topIn = imgTop;
        }

        public byte[] dispCoordToByteArray(DisplayCoordinates displayCoordinates) {
            Field field[] = this.getClass().getDeclaredFields();        // getDeclaredFields() returns fields in any order
            byte[] dispCoordBytes = new byte[field.length * 2];
            try {
                for (int i = 0; i < field.length; i++) {
                    //Utils.log(""+getFieldIndex(field[i/2].getName()));
                    dispCoordBytes[2*getFieldIndex(field[i].getName())] = (byte) (field[i].getInt(displayCoordinates) >> 8 * 0 & 0xFF);
                    dispCoordBytes[2*getFieldIndex(field[i].getName()) + 1] = (byte) (field[i].getInt(displayCoordinates) >> 8 * 1 & 0xFF);
                }
            } catch (IllegalAccessException e) {
                Utils.log("IllegalAccessException in DisplayCoordinates");
                e.printStackTrace();
            }

            return dispCoordBytes;
        }

    }
}
