package ru.sash0k.bluetooth_terminal.activity;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
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
import android.widget.ImageView;
import android.widget.TextView;

import com.actionbarsherlock.app.ActionBar;
import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuItem;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.Serializable;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;

import Catalano.Imaging.FastBitmap;
import Catalano.Imaging.Filters.HistogramAdjust;
import ru.sash0k.bluetooth_terminal.DeviceData;
import ru.sash0k.bluetooth_terminal.R;
import ru.sash0k.bluetooth_terminal.Utils;
import ru.sash0k.bluetooth_terminal.bluetooth.DeviceConnector;
import ru.sash0k.bluetooth_terminal.bluetooth.DeviceListActivity;

public final class DeviceControlActivity extends BaseActivity {
    private static final String DEVICE_NAME = "DEVICE_NAME";
    private static final String LOG = "LOG";

    private static float redWeight = 0.2126f;
    private static float greenWeight = 0.7152f;
    private static float blueWeight = 0.0722f;


    private static final SimpleDateFormat timeformat = new SimpleDateFormat("HH:mm:ss.SSS");

    private static String MSG_NOT_CONNECTED;
    private static String MSG_CONNECTING;
    private static String MSG_CONNECTED;

    private static byte fileData[];

    private static String fileNameExt;

    private static int fileTotalSize = 0;
    private static int fileBytesStart = 0;
    private static int fileBytesEnd = 0;

    private static int maxDataPacketSizeBytes = 329;    // This is a hard constant. 329
    private static int maxPayloadSizeBytes = maxDataPacketSizeBytes - 5;   // This is a hard
    // constant. 329-5=324
    private static int maxNumDataPacketsInBatch = 100;    // Number of data packets in one
    // "batch" of write operation

    private static int imageWidth;
    private static int imageHeight;

    // Display hardware (EPD) dependent constants. Ideally, they should be read from the hardware
    // itself
    // The display pixels should always be even numbers
    private static int displayWidth = 1280;//0;
    private static int displayHeight = 960;//0;
//    private static int displayWidth = 800;//0;
//    private static int displayHeight = 600;//0;

    private static boolean fourBitPixels = true;       // File data packet will have
    // two-bytes-to-one compression
    private static String pnmMagicNumber = "P5";       // Depends on Hardware. P5 for grayscale,
    // P6 for RGB (color)
    private static int maxColorValue = 255;

    private static String ack = "1";

    private static int cntr = 0;

    private enum dataPacketType {
        CMD_PREP_FILE_TRANSFER,
        PNM_FILE_HEADER,
        DATA_FILE_CHUNK,
        CMD_END_OF_FILE,
        CMD_UNMOUNT_SDCARD,
        CMD_DISPLAY_IMAGE,
        CMD_DEFAULT

    }

    private enum dataPacketCompression {
        NO_COMPRESSION,
        TWO_BYTES_TO_ONE_COMPRESSION
    }

    private enum fileIDs {
        ECODE_FILE_ID(1),
        VCOM_FILE_ID(2),
        WAVEFORM_FILE_ID(3),
        HWINFO_FILE_ID(4),
        REG_OVERRIDE_FILE_ID(5),
        RECEIVED_IMG_FILE_ID(6);

        private int value;

        fileIDs(int value) {
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

    private boolean hexMode, needClean;
    private boolean show_timings, show_direction;
    private String command_ending;
    private String deviceName;

    private void dumpFile(String fileName) {
        // get the path to sdcard
        File sdcard = Environment.getExternalStorageDirectory();
        // to this path add a new directory path
        File dir = new File(sdcard.getAbsolutePath() + "/sticker");
// create this directory if not already created
        dir.mkdirs();
        // create the file in which we will write the contents
        File file = new File(dir, fileName);

        try {
            FileOutputStream f = new FileOutputStream(file);
            String data = "This is the content of my file";
            f.write(data.getBytes());
            f.close();
            Utils.log("Written to file");

        } catch (Exception e) {
            Utils.log("Fail: " + e.getMessage());
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PreferenceManager.setDefaultValues(this, R.xml.settings_activity, false);

        dumpFile("sticker.bin");
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

    private boolean isConnected() {
        return (connector != null) && (connector.getState() == DeviceConnector.STATE_CONNECTED);
    }
    // ==========================================================================

    private void stopConnection() {
        if (connector != null) {
            connector.stop();
            connector = null;
            deviceName = null;
        }
    }
    // ==========================================================================

    private void startDeviceListActivity() {
        stopConnection();
        Intent serverIntent = new Intent(this, DeviceListActivity.class);
        startActivityForResult(serverIntent, REQUEST_CONNECT_DEVICE);
    }
    // ============================================================================

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
            commandEditText.setInputType(InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType
                    .TYPE_TEXT_FLAG_CAP_CHARACTERS);
            commandEditText.setFilters(new InputFilter[]{new Utils.InputFilterHex()});
        } else {
            commandEditText.setInputType(InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            commandEditText.setFilters(new InputFilter[]{});
        }

        this.command_ending = getCommandEnding();

        this.show_timings = Utils.getBooleanPrefence(this, getString(R.string.pref_log_timing));
        this.show_direction = Utils.getBooleanPrefence(this, getString(R.string
                .pref_log_direction));
        this.needClean = Utils.getBooleanPrefence(this, getString(R.string.pref_need_clean));
    }
    // ============================================================================

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

                    try {
                        Bitmap bitmapFile = decodeSampledBitmapFromFile(getBaseContext(), fileUri);

                        Bitmap bmp2 = bitmapFile.copy(bitmapFile
                                .getConfig(), true);

                        bmp2 = toGrayscale(bmp2);

                        bmp2 = getResizedBitmap(bmp2);

                        ImageView image = (ImageView) findViewById(R.id.imageView);
                        image.setImageBitmap(bmp2);

                        bitmapFile = getResizedBitmap(bitmapFile);

                        bitmapFile = enhanceBitmap(bitmapFile);

                        ImageView image1 = (ImageView) findViewById(R.id.imageView2);
                        image1.setImageBitmap(bitmapFile);

                        imageWidth = bitmapFile.getWidth();
                        imageHeight = bitmapFile.getHeight();

                        Utils.log("Img Width: " + imageWidth + "Height: " + imageHeight);

                        ByteBuffer buffer = ByteBuffer.allocate((int) bitmapFile.getByteCount());
                        //Create a new buffer
                        bitmapFile.copyPixelsToBuffer(buffer); //Move the byte data to the buffer

                        fileData = new byte[buffer.array().length / 4];

                        for (int i = 0; i < fileData.length; i++) {
                            // buffer.array() is still of size width x height x 4, even after
                            // grayscale conversion,
                            // where R,G,B elements are all the same and transparency is always 255.
                            // So for our purpose, we select every 4th element of the array
                            fileData[i] = buffer.array()[4 * i];
                        }

                        fileTotalSize = fileData.length;

                        int i = fileUri.toString().lastIndexOf('.');
                        if (i >= 0)
                            fileNameExt = fileUri.toString().substring(i + 1);

                        Utils.log("Bitmap size = " + fileTotalSize);

                        Utils.log("sdcard: " + fileUri + " total size: " + fileTotalSize);

                        fileBytesStart = 0;
                        currentDataPacketType = dataPacketType.CMD_PREP_FILE_TRANSFER;
                        nextDataPacketType = dataPacketType.CMD_PREP_FILE_TRANSFER;
                        sendFile();
                    } catch (Exception e) {
                        Utils.log(e.getMessage());
                        break;
                    }
                }

                break;
            }
        }
    }


    public static Bitmap decodeSampledBitmapFromFile(Context ct, Uri fileUri) {

        InputStream in = null;
        InputStream in2 = null;

        Bitmap bm = null;
        // First decode with inJustDecodeBounds=true to check dimensions
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;

        try {
            in = ct.getContentResolver().openInputStream(fileUri);
            BitmapFactory.decodeStream(in, null, options);

            in2 = ct.getContentResolver().openInputStream(fileUri);
            //in.reset();

            Utils.log("Image height = " + options.outHeight + " width = " + options.outWidth + " " +
                    "MIME type = " + options.outMimeType);

            // Decode bitmap with inSampleSize set
            options.inJustDecodeBounds = false;
            bm = BitmapFactory.decodeStream(in2, null, options);
            in.close();
            in2.close();
        } catch (Exception e) {
            Utils.log("Reset fail " + e.getMessage());
        }

        return bm;
    }

    public Bitmap toGrayscale(Bitmap bmpOriginal) {
        int width, height;
        height = bmpOriginal.getHeight();
        width = bmpOriginal.getWidth();

        Bitmap bmpGrayscale = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmpGrayscale);
        Paint paint = new Paint();
        //    ColorMatrix cm = new ColorMatrix();
        float[] mat = new float[]{
                redWeight, greenWeight, blueWeight, 0, 0,
                redWeight, greenWeight, blueWeight, 0, 0,
                redWeight, greenWeight, blueWeight, 0, 0,
                0, 0, 0, 1, 0
        };
        ColorMatrix cm = new ColorMatrix(mat);
        cm.setSaturation(0);
        ColorMatrixColorFilter f = new ColorMatrixColorFilter(cm);
        paint.setColorFilter(f);
        c.drawBitmap(bmpOriginal, 0, 0, paint);


        return bmpGrayscale;
    }

    public Bitmap enhanceBitmap(Bitmap b) {
        FastBitmap fbm = new FastBitmap(b);

        HistogramAdjust corr1 = new HistogramAdjust(0.01);

        fbm.toGrayscale();

        corr1.applyInPlace(fbm);

        return fbm.toBitmap();
    }

    /**
     * @param bmp        input bitmap
     * @param contrast   0..10 1 is default
     * @param brightness -255..255 0 is default
     * @return new bitmap
     */
    public static Bitmap changeBitmapContrastBrightness(Bitmap bmp, float contrast, float
            brightness) {
        ColorMatrix cm = new ColorMatrix(new float[]
                {
                        contrast, 0, 0, 0, brightness,
                        0, contrast, 0, 0, brightness,
                        0, 0, contrast, 0, brightness,
                        0, 0, 0, 1, 0
                });

        Bitmap ret = Bitmap.createBitmap(bmp.getWidth(), bmp.getHeight(), bmp.getConfig());

        Canvas canvas = new Canvas(ret);

        Paint paint = new Paint();
        paint.setColorFilter(new ColorMatrixColorFilter(cm));
        canvas.drawBitmap(bmp, 0, 0, paint);

        return ret;
    }

    public Bitmap getResizedBitmap(Bitmap b) {

        int imgWidth = b.getWidth();
        int imgHeight = b.getHeight();

        //changing to nearest even number
        imgWidth &= ~1;
        imgHeight &= ~1;

        Matrix m = new Matrix();
        m.setRectToRect(new RectF(0, 0, imgWidth, imgHeight), new RectF(0, 0, displayWidth,
                displayHeight), Matrix.ScaleToFit.CENTER);

        Bitmap new_bm = Bitmap.createBitmap(b, 0, 0, imgWidth, imgHeight, m, true);

        //the scaled image might contain odd pixels, need to confirm and change to even
        imgWidth = new_bm.getWidth();
        imgHeight = new_bm.getHeight();
        if ((1 == (imgWidth & 1)) | (1 == (imgHeight & 1))) {
            return new_bm.createScaledBitmap(new_bm, (imgWidth & ~1), (imgHeight & ~1), true);
        }

        return new_bm;
    }

    // ==========================================================================


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


    public void sendCommand(View view) {
        if (commandEditText != null) {
            String commandString = commandEditText.getText().toString();
            if (commandString.isEmpty()) return;

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

        final int scrollAmount = logTextView.getLayout().getLineTop(logTextView.getLineCount()) -
                logTextView.getHeight();
        if (scrollAmount > 0)
            logTextView.scrollTo(0, scrollAmount);
        else logTextView.scrollTo(0, 0);

        if (clean) commandEditText.setText("");
    }
    // =========================================================================

    // =========================================================================

    public byte[] createDataPacket() {
        byte id;
        byte comp;
        short length;
        byte[] payload_tmp;
        byte[] payload;
        byte trailer;
        byte[] dataPacketBytes;


        switch (currentDataPacketType) {

            case CMD_PREP_FILE_TRANSFER:
                Utils.log("Prep for file transfer");
                id = (byte) dataPacketType.CMD_PREP_FILE_TRANSFER.ordinal();
                comp = (byte) dataPacketCompression.NO_COMPRESSION.ordinal();

                // Positioning image in the center of the display and displaying full image (no
                // cropping)
                DisplayCoordinates displayCoordinates = new DisplayCoordinates((displayWidth -
                        imageWidth) / 2, (displayHeight - imageHeight) / 2, imageWidth,
                        imageHeight, 0, 0);

                payload = displayCoordinates.dispCoordToByteArray(displayCoordinates);
                length = (short) payload.length;
                Utils.log(" payload length = " + length);
                trailer = '\n';
                nextDataPacketType = dataPacketType.PNM_FILE_HEADER;
                break;

            case PNM_FILE_HEADER:
                id = (byte) dataPacketType.PNM_FILE_HEADER.ordinal();
                comp = (byte) dataPacketCompression.NO_COMPRESSION.ordinal();

                String header = String.format("%s\n%d %d\n%d\n", pnmMagicNumber, imageWidth,
                        imageHeight, maxColorValue);

                payload = header.getBytes();
                length = (short) payload.length;
                trailer = '\n';
                nextDataPacketType = dataPacketType.DATA_FILE_CHUNK;
                break;

            case DATA_FILE_CHUNK:
                id = (byte) dataPacketType.DATA_FILE_CHUNK.ordinal();
                comp = (byte) dataPacketCompression.TWO_BYTES_TO_ONE_COMPRESSION.ordinal();
                nextDataPacketType = dataPacketType.DATA_FILE_CHUNK;
                if (fourBitPixels == true)
                    fileBytesEnd = fileBytesStart + maxPayloadSizeBytes * 2;
                else
                    fileBytesEnd = fileBytesStart + maxPayloadSizeBytes;

                if (fileBytesEnd >= fileTotalSize) {
                    fileBytesEnd = fileTotalSize;
                    Utils.log("Sending last chunk of data");
                    nextDataPacketType = dataPacketType.CMD_END_OF_FILE;
                }

                if (fourBitPixels == true) {
                    payload_tmp = Arrays.copyOfRange(fileData, fileBytesStart, fileBytesEnd);
                    payload = new byte[payload_tmp.length / 2];
                    for (int i = 0; i < payload.length; i++) {
                        payload[i] = (byte) ((payload_tmp[2 * i] & 0xF0) | ((payload_tmp[2 * i +
                                1] & 0xF0) >> 4));
                    }
                } else {
                    payload = Arrays.copyOfRange(fileData, fileBytesStart, fileBytesEnd);
                }

                //Utils.log("Sending bytes: " + fileBytesStart + " to bytes " + fileBytesEnd);

                length = (short) payload.length;
                trailer = (byte) cntr++;         // For debugging
                fileBytesStart = fileBytesEnd;
                break;

            case CMD_END_OF_FILE:
                Utils.log("End of File");
                id = (byte) dataPacketType.CMD_END_OF_FILE.ordinal();
                comp = (byte) dataPacketCompression.NO_COMPRESSION.ordinal();
                payload = new byte[]{0};
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
                payload = new byte[]{(byte) fileIDs.RECEIVED_IMG_FILE_ID.getValue()};
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

        DataPacket dataPacket = new DataPacket(id, comp, length, payload, trailer);

        dataPacketBytes = dataPacket.dataPacketToByteArray(dataPacket);
        return dataPacketBytes;


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

    public void sendBtDataPacketsBatch() {
        int count = 0;
        byte[] dataPacketsBatch_tmp = new byte[maxNumDataPacketsInBatch * maxDataPacketSizeBytes];
        int batchSize = 0;
        do {
            byte[] dataPacketBytes = createDataPacket();
            System.arraycopy(dataPacketBytes, 0, dataPacketsBatch_tmp, batchSize, dataPacketBytes
                    .length);
            batchSize += dataPacketBytes.length;
            count++;
        } while (nextDataPacketType == currentDataPacketType && count < maxNumDataPacketsInBatch);

        currentDataPacketType = nextDataPacketType;
        byte[] dataPacketsBatch = new byte[batchSize];
        System.arraycopy(dataPacketsBatch_tmp, 0, dataPacketsBatch, 0, batchSize);
        Utils.log("Length of dataPacketBytes: " + dataPacketsBatch.length);
        if (isConnected()) {
            connector.write(dataPacketsBatch);
        }
    }

    public void sendFile() {
        cntr = 0;                 // For debugging, cntr can be used to tag each data packet as
        // its trailer
        do {
            sendBtDataPacketsBatch();
        } while (nextDataPacketType != dataPacketType.CMD_DEFAULT);
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

                        if (readMessage.contains(ack)) {
                            Utils.log("ack rx: ");
                        } else {
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

        // TODO: Not a good code design with all the hard coding but Serialization doesn't work
        // as in C
        // Need to come up with a better way
        public byte[] dataPacketToByteArray(DataPacket dataPacket) {
            byte[] dataPacketBytes = new byte[dataPacket.dataPacketPayload.length + 5];
            dataPacketBytes[0] = dataPacket.dataPacketID;
            dataPacketBytes[1] = dataPacket.dataPacketComp;
            dataPacketBytes[2] = (byte) (dataPacket.dataPacketLength >> 8 * 0 & 0xFF);
            dataPacketBytes[3] = (byte) (dataPacket.dataPacketLength >> 8 * 1 & 0xFF);
            System.arraycopy(dataPacket.dataPacketPayload, 0, dataPacketBytes, 4, dataPacket
                    .dataPacketPayload.length);
            dataPacketBytes[dataPacketBytes.length - 1] = dataPacket.dataPacketTrailer;
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

    private static class DisplayCoordinates {
        private int leftOut;
        private int topOut;
        private int widthOut;
        private int heightOut;
        private int leftIn;
        private int topIn;

        private int getFieldIndex(String string) {
            String fields[] = new String[]{"leftOut", "topOut", "widthOut", "heightOut",
                    "leftIn", "topIn"};    // This sequence should match what is expected at the
            // receiving end
            return Arrays.asList(fields).indexOf(string);
        }

        public DisplayCoordinates(int areaLeft, int areaTop, int areaWidth, int areaHeight, int
                imgLeft, int imgTop) {
            leftOut = areaLeft;
            topOut = areaTop;
            widthOut = areaWidth;
            heightOut = areaHeight;
            leftIn = imgLeft;
            topIn = imgTop;
        }

        public byte[] dispCoordToByteArray(DisplayCoordinates displayCoordinates) {
            Field field[] = this.getClass().getDeclaredFields();        // getDeclaredFields()
            // returns fields in any order
            byte[] dispCoordBytes = new byte[field.length * 2];
            try {
                for (int i = 0; i < field.length; i++) {
                    dispCoordBytes[2 * getFieldIndex(field[i].getName())] = (byte) (field[i]
                            .getInt(displayCoordinates) >> 8 * 0 & 0xFF);
                    dispCoordBytes[2 * getFieldIndex(field[i].getName()) + 1] = (byte) (field[i]
                            .getInt(displayCoordinates) >> 8 * 1 & 0xFF);
                }
            } catch (IllegalAccessException e) {
                Utils.log("IllegalAccessException in DisplayCoordinates");
                e.printStackTrace();
            }

            return dispCoordBytes;
        }

    }
}
