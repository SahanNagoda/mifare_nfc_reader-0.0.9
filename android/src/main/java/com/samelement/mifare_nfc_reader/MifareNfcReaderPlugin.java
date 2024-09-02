package com.samelement.mifare_nfc_reader;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.acs.smartcard.Reader;
import com.acs.smartcard.ReaderException;

import java.util.ArrayList;
import java.util.List;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;

/**
 * MifareNfcReaderPlugin
 */
public class MifareNfcReaderPlugin implements FlutterPlugin, MethodCallHandler {
    /// The MethodChannel that will the communication between Flutter and native Android
    ///
    /// This local reference serves to register the plugin with the Flutter Engine and unregister it
    /// when the Flutter Engine is detached from the Activity
    private MethodChannel channel;
    private Context pluginContext;

    private UsbManager mManager;
    private Reader mReader;
    private PendingIntent mPermissionIntent;
    private static final String ACTION_USB_PERMISSION = "com.samelement.mifare_nfc_reader.USB_PERMISSION";
    private static final String READER_EXCEPTION = "readerException";

    private static final String[] stateStrings = {"Unknown", "Absent",
            "Present", "Swallowed", "Powered", "Negotiable", "Specific"};

    private final Handler handler = new Handler(Looper.getMainLooper());


    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
        channel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(), "mifare_nfc_reader");
        pluginContext = flutterPluginBinding.getApplicationContext();
        channel.setMethodCallHandler(this);
        registerReceiver();
    }

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
        if ("init".equals(call.method)) {
            UsbDevice usbDevice = getConnectedReader();
            if (usbDevice != null) {
                onUsbDeviceStateChanged("Attached");
                requestPermission(usbDevice);
                result.success(true);
            } else {
                onUsbDeviceStateChanged("Detached");
                result.success(false);
            }
        } else if ("writeText".equals(call.method)) {
            try {
                boolean writeSuccessful = writeTextToCard(call.argument("text"));
                if (writeSuccessful) result.success(true);
                else result.success(false);
            } catch (ReaderException e) {
                e.printStackTrace();
                result.error(READER_EXCEPTION, e.getMessage(), null);
            }
        } else if ("writeJson".equals(call.method)) {
            try {
                boolean writeSuccessful = writeJsonToCard(call.argument("json"));
                if (writeSuccessful) result.success(true);
                else result.success(false);
            } catch (ReaderException e) {
                e.printStackTrace();
                result.error(READER_EXCEPTION, e.getMessage(), null);
            }
        } else if ("clearCard".equals(call.method)) {
            try {
                StringBuilder builder = new StringBuilder();
                builder.append(MifareCommand.clearCommand());
                boolean writeSuccessful = write(builder);
                if (writeSuccessful) result.success(true);
                else result.success(false);
            } catch (ReaderException e) {
                e.printStackTrace();
                result.error(READER_EXCEPTION, e.getMessage(), null);
            }
        } else {
            result.notImplemented();
        }
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        channel.setMethodCallHandler(null);
    }

    private void requestPermission(UsbDevice device) {
        getUsbManager().requestPermission(device, mPermissionIntent);
    }

    private boolean writeJsonToCard(String json) throws ReaderException {
        String jsonHex = HexUtils.asciiToHex(json);

        int payloadLength = json.length();
        String payloadLengthHex = HexUtils.toHexString(payloadLength);
        if (payloadLength > 255) {
            payloadLengthHex = HexUtils.decimalToFourBytesHex(payloadLength);
        }

        // build record header
        String messageBegin = "1";
        String messageEnd = "1";
        String chunkFlag = "0";
        String shortRecord = payloadLength > 255 ? "0" : "1";
        String idLength = "0";
        String typeNameFormat = "010";

        String recordHeaderBin = messageBegin + messageEnd + chunkFlag + shortRecord + idLength + typeNameFormat;
        String recordHeader = HexUtils.binaryStrToHex(recordHeaderBin);
        String typeLength = "10";
        String typeField = "6170706C69636174696F6E2F6A736F6E"; // application/json

        StringBuilder blockDataBuilder = new StringBuilder();
        blockDataBuilder.append(recordHeader);
        blockDataBuilder.append(typeLength);
        blockDataBuilder.append(payloadLengthHex);
        blockDataBuilder.append(typeField);
        blockDataBuilder.append(jsonHex);

        return write(blockDataBuilder);
    }

    private boolean writeTextToCard(String text) throws ReaderException {
        String prefixHex = "02656E"; // 2en
        String textHex = HexUtils.asciiToHex(text);
        int payloadLength = text.length() + 3;
        String payloadLengthHex = HexUtils.toHexString(payloadLength);
        if (payloadLength > 255) {
            payloadLengthHex = HexUtils.decimalToFourBytesHex(payloadLength);
        }

        // build record header
        String messageBegin = "1";
        String messageEnd = "1";
        String chunkFlag = "0";
        String shortRecord = payloadLength > 255 ? "0" : "1";
        String idLength = "0";
        String typeNameFormat = "001";

        String recordHeaderBin = messageBegin + messageEnd + chunkFlag + shortRecord + idLength + typeNameFormat;
        String recordHeader = HexUtils.binaryStrToHex(recordHeaderBin);
        String typeLength = "01";
        String typeField = "54"; // T

        StringBuilder blockDataBuilder = new StringBuilder();
        blockDataBuilder.append(recordHeader);
        blockDataBuilder.append(typeLength);
        blockDataBuilder.append(payloadLengthHex);
        blockDataBuilder.append(typeField);
        blockDataBuilder.append(prefixHex);
        blockDataBuilder.append(textHex);

        return write(blockDataBuilder);
    }

    private boolean writeEmpty() throws ReaderException {
        boolean success = true;
        int trailerBlock = 7;
        int blockNumber = 4;
        boolean isSectorAuthenticated = loadsAuthenticationKey() && authentication(trailerBlock);
        if (isSectorAuthenticated) {
            int slotNum = 0;
            byte[] command;
            byte[] response = new byte[65538];
            int responseLength;

            command = HexUtils.toByteArray(MifareCommand.clearCommand());
            System.out.println("command " + HexUtils.paddingTo16Bytes(MifareCommand.clearCommand()));
            responseLength = getReader().transmit(slotNum, command, command.length, response, response.length);

            StringBuilder bufferString = new StringBuilder();
            List<String> hexResults = new ArrayList<>();

            for (int i = 0; i < responseLength; i++) {

                String hexChar = Integer.toHexString(response[i] & 0xFF);
                if (hexChar.length() == 1) {
                    hexChar = "0" + hexChar;
                }

                hexResults.add(hexChar);
                bufferString.append(hexChar.toUpperCase());
            }

            System.out.println("Eh resultnya " + bufferString.toString());

            if (hexResults.size() > 1 &&
                    hexResults.get(hexResults.size() - 2).equals("90") &&
                    hexResults.get(hexResults.size() - 1).equals("00")
            ) {
                System.out.println("Write to block " + blockNumber + " successful ");
            } else {
                System.out.println("Error write card");
                success = false;
            }
        } else {
            success = false;
        }

        return success;
    }

    private boolean write(StringBuilder blockDataBuilder) throws ReaderException {
        boolean success = true;

        System.out.println("Block data builder " + blockDataBuilder.toString());
        int lengthBlockData = blockDataBuilder.length() / 2;
        System.out.println("Length block data " + lengthBlockData);

        String ndefMessageLength;

        String tlvPadding = "0000";
        String tlvNdefMessage = "03";

        if (lengthBlockData > 255) {
            ndefMessageLength = "FF" + HexUtils.decimalToTwoBytesHex(lengthBlockData);
        } else {
            ndefMessageLength = HexUtils.toHexString(lengthBlockData);
        }

        StringBuilder ndefBuilder = new StringBuilder();
        ndefBuilder.append(tlvPadding);
        ndefBuilder.append(tlvNdefMessage);
        ndefBuilder.append(ndefMessageLength);
        ndefBuilder.append(blockDataBuilder.toString());
        ndefBuilder.append("FE");

        System.out.println("NDEF : " + ndefBuilder.toString());
        // dibagi tiap block

        List<String> ndefSplit = HexUtils.usingSplitMethod(ndefBuilder.toString(), 32);
        System.out.println("Ndef after split : " + ndefSplit.toString());

        int index = 0;
        List<Integer> authenticatedSector = new ArrayList<>();

        int blockNumber = 4;
        while (index < ndefSplit.size()) {
            if ((blockNumber + 1) % 4 != 0) {
                int sector = blockNumber / 4;
                boolean isSectorAuthenticated;

                if (!authenticatedSector.contains(sector)) {
                    int trailerBlock = ((sector + 1) * 4) - 1;
                    isSectorAuthenticated = loadsAuthenticationKey() && authentication(trailerBlock);
                    if (isSectorAuthenticated) {
                        System.out.println("Sector " + sector + " authenticated");
                        authenticatedSector.add(sector);
                    }
                } else {
                    isSectorAuthenticated = true;
                    System.out.println("Sector " + sector + " already authenticated");
                }

                if (isSectorAuthenticated) {
                    int slotNum = 0;
                    byte[] command;
                    byte[] response = new byte[65538];
                    int responseLength;

                    System.out.println("Mau write " + HexUtils.paddingTo16Bytes(ndefSplit.get(index)));

                    command = HexUtils.toByteArray(MifareCommand.updateBlockCommand(blockNumber, HexUtils.paddingTo16Bytes(ndefSplit.get(index))));
                    responseLength = getReader().transmit(slotNum, command, command.length, response, response.length);

                    StringBuilder bufferString = new StringBuilder();
                    List<String> hexResults = new ArrayList<>();

                    for (int i = 0; i < responseLength; i++) {

                        String hexChar = Integer.toHexString(response[i] & 0xFF);
                        if (hexChar.length() == 1) {
                            hexChar = "0" + hexChar;
                        }

                        hexResults.add(hexChar);
                        bufferString.append(hexChar.toUpperCase());
                    }

                    System.out.println("Eh resultnya " + bufferString.toString());

                    if (hexResults.size() > 1 &&
                            hexResults.get(hexResults.size() - 2).equals("90") &&
                            hexResults.get(hexResults.size() - 1).equals("00")
                    ) {
                        System.out.println("Write to block " + blockNumber + " successful ");
                    } else {
                        System.out.println("Error write card");
                        success = false;
                        break;
                    }
                }

                index++;
            }

            blockNumber++;

        }

        return success;
    }

    private void readCard() throws ReaderException {
        System.out.println("Read Card");
        int slotNum = 0;

        getReader().power(slotNum, Reader.CARD_WARM_RESET);
        getReader().setProtocol(slotNum, Reader.PROTOCOL_T0 | Reader.PROTOCOL_T1);

        byte[] command;
        byte[] response = new byte[65538];
        int responseLength;

        command = HexUtils.toByteArray(MifareCommand.GET_UID_COMMAND);

        responseLength = getReader().transmit(slotNum, command, command.length, response, response.length);

        StringBuilder bufferString = new StringBuilder();
        List<String> hexResults = new ArrayList<>();

        for (int i = 0; i < responseLength; i++) {

            String hexChar = Integer.toHexString(response[i] & 0xFF);
            if (hexChar.length() == 1) {
                hexChar = "0" + hexChar;
            }

            hexResults.add(hexChar);
            bufferString.append(hexChar.toUpperCase());
        }

        if (hexResults.size() > 1 &&
                hexResults.get(hexResults.size() - 2).equals("90") &&
                hexResults.get(hexResults.size() - 1).equals("00")
        ) {
            // get UID
            String UID = bufferString.substring(0, 8);
            onReadUID(UID);

            // get NDEF message
            List<String> ndefMessages = readNDEFMessage();
            onReceiveNdefMessage(ndefMessages);
        } else {
            onReadCardError();
        }
    }

    private List<String> readBlock(int blockNumber) throws ReaderException {
        int slotNum = 0;
        byte[] command;
        byte[] response = new byte[65538];
        int responseLength;

        command = HexUtils.toByteArray(MifareCommand.readDataBlockCommand(blockNumber));

        responseLength = getReader().transmit(slotNum, command, command.length, response, response.length);

        List<String> responseList = getResponseList(response, responseLength);
        System.out.println("Read Block " + blockNumber + " : " + responseList.toString());
        if (responseList.size() > 0 && responseList.get(responseList.size() - 1).equals("00")
                && responseList.get(responseList.size() - 2).equals("90")) {
            return responseList.subList(0, responseList.size() - 2);
        }

        return new ArrayList<>();
    }

    private String hexToAscii(String[] hexPayload) {
        StringBuilder asciiText = new StringBuilder();

        for (String hex : hexPayload) {
            // Convert each hex string to an integer
            int decimalValue = Integer.parseInt(hex, 16);

            // Convert the integer to its ASCII equivalent if it's a readable character
            if (decimalValue >= 32 && decimalValue <= 126) {
                asciiText.append((char) decimalValue);
            }
        }

        return asciiText.toString();
    }


    private List<String> readNDEFMessage() throws ReaderException {
        System.out.println("ACRReader: Read NDEF Message");
        int blockNumber = 5;
        final int startingBlockNo = 5;
        int ndefMessageLength = 0;
        int paddingCount = 0;

        boolean loop = true;
        List<Integer> authenticatedSector = new ArrayList<>();
        List<String> ndefMessage = new ArrayList<>();

        while (loop) {
            // if ((blockNumber + 1) % 4 == 0) {
            //     blockNumber++;
            //     continue;
            // }

            if (blockNumber > 47) {
                break;
            }

            int sector = blockNumber / 4;
            boolean isSectorAuthenticated;

            if (!authenticatedSector.contains(sector)) {
                System.out.println("ACRReader: Authenticate Sector " + sector);
                int trailerBlock = ((sector + 1) * 4) - 1;
                isSectorAuthenticated = loadsAuthenticationKey();// && authentication(trailerBlock);
                if (isSectorAuthenticated) {
                    System.out.println("ACRReader: Sector " + sector + " authenticated");
                    authenticatedSector.add(sector);
                } else {
                    System.out.println("ACRReader: Sector " + sector + " authenticate Fail");
                }
            } else {
                isSectorAuthenticated = true;
                System.out.println("ACRReader: Sector " + sector + " already authenticated");
            }

            if (isSectorAuthenticated) {
                System.out.println("ACRReader: Read Block Number : " + blockNumber);
                List<String> message = readBlock(blockNumber);
                // I can not found ndef message
                if (ndefMessageLength == 0) {
                    
                    int index = 0;
                    for (String hexChar : message) {
                        // read TLV message
                        if (hexChar.equals("03")) {
                            // this is a NDEF Message
                            index++;
                            // read length of a NDEF Message
                            if (message.get(index).equals("FF")) {
                                // read two more bytes for length
                                ndefMessageLength = HexUtils.toHexDecimal(message.get(index + 1) + message.get(index + 2));
                                index = index + 2;
                            } else {
                                ndefMessageLength = HexUtils.toHexDecimal(message.get(index));
                                System.out.println("ACRReader: 1: NDEF message length " + ndefMessageLength);
                            }
                            index++;


                            paddingCount = index;
                            break;
                        }
                        index++;
                    }

                    if (ndefMessageLength == 0){ 
                        System.out.println("NDEF message length is zero");
                        loop = false;
                    }
                    else {
                        System.out.println("ACRReader: 2: NDEF message length " + ndefMessageLength);

                        List<String> blockContent = message.subList(paddingCount, message.size());
                        if (ndefMessageLength > blockContent.size()) {
                            ndefMessage.addAll(message.subList(paddingCount, message.size()));
                        } else {
                            ndefMessage.addAll(message.subList(paddingCount, paddingCount + ndefMessageLength));
                            loop = false;
                        }
                    }
                } else {
                    int remainNdefMessageLength = (((ndefMessageLength - (startingBlockNo - 1)) * 4) - ndefMessage.size()) - paddingCount;
                    System.out.println("ACRReader: Remaining NDEF Message Length: " + remainNdefMessageLength);
                    if (remainNdefMessageLength > message.size()) {
                        ndefMessage.addAll(message);
                        System.out.println("ACRReader: Adding All the Messages");
                    } else {
                        ndefMessage.addAll(message.subList(0, remainNdefMessageLength));
                        System.out.println("ACRReader: Adding remainNdefMessageLength Messages");
                        loop = false;
                    }
                }

            }

            blockNumber++;
        }

        System.out.println("All NDEF Message " + ndefMessage.toString());

        List<NdefBlock> blocks = new ArrayList<>();
        final int messageByteLength = ((ndefMessageLength - (startingBlockNo - 1)) * 4) - paddingCount;
        for (int k = 0; k < messageByteLength; k++) {
            // find first message
            
            System.out.println("ACRReader: Final NDEF message length " + messageByteLength);
            int blockIndex = NdefBlockUtil.getBlockIndex(ndefMessage.subList(k, messageByteLength));
            System.out.println("NILAI K " + k);
            System.out.println("BLOCK INDEX " + blockIndex);
            if (blockIndex < 0 ){
                NdefBlock block = new NdefBlock(ndefMessage.subList(k, k + (blockIndex * -1)));
                if (!block.isEmptyRecord()) {
                    blocks.add(block);
                }
                break;
            }
            NdefBlock block = new NdefBlock(ndefMessage.subList(k, k + blockIndex));
            if (!block.isEmptyRecord()) {
                blocks.add(block);
            }
            k = k + blockIndex - 1;
        }

        List<String> messages = new ArrayList<>();
        for (NdefBlock block : blocks) {
            System.out.println("BEGIN : " + block.isMessageBegin());
            System.out.println("END : " + block.isMessageEnd());
            System.out.println("PAYLOAD : " + block.getPayload());

            messages.add(block.getPayload());
        }

        return messages;
    }

    private boolean authenticationWithKey(String aKeyCommand) throws ReaderException {
        int slotNum = 0;
        byte[] command;
        byte[] response = new byte[65538];
        int responseLength;

        command = HexUtils.toByteArray(aKeyCommand);

        responseLength = getReader().transmit(slotNum, command, command.length, response, response.length);
        String responseString = getResponseString(response, responseLength);
        System.out.println("Authentication response " + responseString);
        return responseString.equals("9000");
    }

    private boolean authentication(int blockNumber) throws ReaderException {
        if (authenticationWithKey(MifareCommand.getAuthenticationKeyACommand(blockNumber))) {
            return true;
        } else {
            return authenticationWithKey(MifareCommand.getAuthenticationKeyBCommand(blockNumber));
        }
    }

    private List<String> getResponseList(byte[] response, int responseLength) {
        List<String> responseList = new ArrayList<>();

        for (int i = 0; i < responseLength; i++) {

            String hexChar = Integer.toHexString(response[i] & 0xFF);
            if (hexChar.length() == 1) {
                hexChar = "0" + hexChar;
            }
            responseList.add(hexChar.toUpperCase());
        }
        return responseList;
    }

    private String getResponseString(byte[] response, int responseLength) {
        StringBuilder bufferString = new StringBuilder();

        for (int i = 0; i < responseLength; i++) {

            String hexChar = Integer.toHexString(response[i] & 0xFF);
            if (hexChar.length() == 1) {
                hexChar = "0" + hexChar;
            }
            bufferString.append(hexChar.toUpperCase());
        }
        return bufferString.toString();
    }

    private boolean loadsAuthenticationKey() throws ReaderException {
        int slotNum = 0;
        byte[] command;
        byte[] response = new byte[65538];
        int responseLength;

        command = HexUtils.toByteArray(MifareCommand.LOAD_AUTH_KEY_COMMAND);

        responseLength = getReader().transmit(slotNum, command, command.length, response, response.length);

        StringBuilder bufferString = new StringBuilder();

        for (int i = 0; i < responseLength; i++) {

            String hexChar = Integer.toHexString(response[i] & 0xFF);
            if (hexChar.length() == 1) {
                hexChar = "0" + hexChar;
            }
            bufferString.append(hexChar.toUpperCase());
        }

        return bufferString.toString().equals("9000");
    }

    private UsbManager getUsbManager() {
        if (mManager == null) {
            mManager = (UsbManager) pluginContext.getSystemService(Context.USB_SERVICE);
        }

        return mManager;
    }

    private Reader getReader() {
        if (mReader == null) {
            mReader = new Reader(getUsbManager());
            mReader.setOnStateChangeListener(onReaderStateChanged);
        }

        return mReader;
    }

    private final Reader.OnStateChangeListener onReaderStateChanged = (slotNum, prevState, currState) -> {

        if (prevState < Reader.CARD_UNKNOWN
                || prevState > Reader.CARD_SPECIFIC) {
            prevState = Reader.CARD_UNKNOWN;
        }

        if (currState < Reader.CARD_UNKNOWN
                || currState > Reader.CARD_SPECIFIC) {
            currState = Reader.CARD_UNKNOWN;
        }

        System.out.println("Previous state : " + stateStrings[prevState]);
        System.out.println("Current state : " + stateStrings[currState]);

        onCardStateChanged(stateStrings[currState]);

        if (stateStrings[prevState].equals("Absent") && stateStrings[currState].equals("Present")) {
            // read card
            try {
                readCard();
            } catch (ReaderException e) {
                e.printStackTrace();
            }
        }
    };

    @SuppressLint("UnspecifiedImmutableFlag")
    private void registerReceiver() {
        Intent intent = new Intent(ACTION_USB_PERMISSION);
        intent.putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
        intent.setPackage(pluginContext.getPackageName());
        mPermissionIntent = PendingIntent.getBroadcast(pluginContext, 0, intent, PendingIntent.FLAG_MUTABLE);
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        pluginContext.registerReceiver(mReceiver, filter);
    }

    private UsbDevice getConnectedReader() {
        List<UsbDevice> connectedUsbDevices = new ArrayList<>();
        for (UsbDevice device : getUsbManager().getDeviceList().values()) {
            if (getReader().isSupported(device)) {
                connectedUsbDevices.add(device);
            }
        }

        if (connectedUsbDevices.size() > 0) {
            return connectedUsbDevices.get(connectedUsbDevices.size() - 1);
        }

        return null;
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {

        public void onReceive(Context context, Intent intent) {

            String action = intent.getAction();
            System.out.println("ACTION " + action);

            switch (action) {
                case UsbManager.ACTION_USB_DEVICE_ATTACHED:
                    Toast.makeText(pluginContext, "A USB device is attached", Toast.LENGTH_SHORT).show();

                    UsbDevice device = intent
                            .getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (getReader().isSupported(device)) {
                        onUsbDeviceStateChanged("Attached");
                        requestPermission(device);
                    }
                    else {
                        Toast.makeText(pluginContext, "The connected USB device is not an ACS smart card reader", Toast.LENGTH_LONG).show();
                    }
                    break;
                case ACTION_USB_PERMISSION:
                    // Toast.makeText(pluginContext, "Accept this permission request to turn on the reader", Toast.LENGTH_LONG).show();
                    synchronized (this) {

                        UsbDevice usbDevice = intent
                                .getParcelableExtra(UsbManager.EXTRA_DEVICE);

                        if (usbDevice != null) {
                            System.out.println("Open Device");
                            getReader().open(usbDevice);
                        } else {
                            Toast.makeText(pluginContext, "Permission denied for device ", Toast.LENGTH_LONG).show();
                        }
                    }

                    break;
                case UsbManager.ACTION_USB_DEVICE_DETACHED:
                    onUsbDeviceStateChanged("Detached");
                    getReader().close();
                    break;
            }
        }
    };


    void onReceiveNdefMessage(List<String> ndefMessages) {
        handler.post(() -> channel.invokeMethod("onReceiveNdefMessages", ndefMessages));
    }

    void onCardStateChanged(String state) {
        // Absent. Present
        handler.post(() -> channel.invokeMethod("onCardStateChanged", state));
    }

    void onUsbDeviceStateChanged(String state) {
        // attached, detached
        handler.post(() -> channel.invokeMethod("onUsbDeviceStateChanged", state));
    }

    void onReadCardError() {
        handler.post(() -> channel.invokeMethod("onReadCardError", "Failed to read the card"));
    }

    void onReadUID(String uid) {
        handler.post(() -> channel.invokeMethod("onReadUID", uid));
    }
}

