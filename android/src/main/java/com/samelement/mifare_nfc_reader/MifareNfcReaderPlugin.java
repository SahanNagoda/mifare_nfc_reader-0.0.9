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
    
    // Add timestamp tracking to prevent rapid repeated calls
    private long lastWriteTextCall = 0;
    private long lastWriteJsonCall = 0;
    private static final long MIN_CALL_INTERVAL = 2000; // 2 seconds minimum between calls

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
        channel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(), "mifare_nfc_reader");
        pluginContext = flutterPluginBinding.getApplicationContext();
        channel.setMethodCallHandler(this);
        registerReceiver();
    }

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
        System.out.println("=== Method Call: " + call.method + " ===");
        
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
            System.out.println("=== writeText called with: " + call.argument("text") + " ===");
            
            // Check for rapid repeated calls
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastWriteTextCall < MIN_CALL_INTERVAL) {
                System.out.println("=== writeText called too frequently, ignoring call ===");
                result.error("RATE_LIMIT", "Method called too frequently", null);
                return;
            }
            lastWriteTextCall = currentTime;
            
            try {
                boolean writeSuccessful = writeTextToCard(call.argument("text"));
                System.out.println("=== writeText result: " + (writeSuccessful ? "SUCCESS" : "FAILED") + " ===");
                if (writeSuccessful) result.success(true);
                else result.success(false);
            } catch (ReaderException e) {
                System.out.println("=== writeText ReaderException: " + e.getMessage() + " ===");
                e.printStackTrace();
                result.error(READER_EXCEPTION, e.getMessage(), null);
            }
        } else if ("writeJson".equals(call.method)) {
            System.out.println("=== writeJson called with: " + call.argument("json") + " ===");
            
            // Check for rapid repeated calls
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastWriteJsonCall < MIN_CALL_INTERVAL) {
                System.out.println("=== writeJson called too frequently, ignoring call ===");
                result.error("RATE_LIMIT", "Method called too frequently", null);
                return;
            }
            lastWriteJsonCall = currentTime;
            
            try {
                boolean writeSuccessful = writeJsonToCard(call.argument("json"));
                System.out.println("=== writeJson result: " + (writeSuccessful ? "SUCCESS" : "FAILED") + " ===");
                if (writeSuccessful) result.success(true);
                else result.success(false);
            } catch (ReaderException e) {
                System.out.println("=== writeJson ReaderException: " + e.getMessage() + " ===");
                e.printStackTrace();
                result.error(READER_EXCEPTION, e.getMessage(), null);
            }
        } else if ("clearCard".equals(call.method)) {
            System.out.println("=== clearCard called ===");
            try {
                StringBuilder builder = new StringBuilder();
                builder.append(MifareCommand.clearCommand());
                boolean writeSuccessful = write(builder);
                System.out.println("=== clearCard result: " + (writeSuccessful ? "SUCCESS" : "FAILED") + " ===");
                if (writeSuccessful) result.success(true);
                else result.success(false);
            } catch (ReaderException e) {
                System.out.println("=== clearCard ReaderException: " + e.getMessage() + " ===");
                e.printStackTrace();
                result.error(READER_EXCEPTION, e.getMessage(), null);
            }
        } else {
            System.out.println("=== Method not implemented: " + call.method + " ===");
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
        System.out.println("=== Starting writeJsonToCard ===");
        System.out.println("Input JSON: " + json);
        System.out.println("JSON length: " + json.length());
        System.out.println("Timestamp: " + System.currentTimeMillis());
        
        // Clear the card first to ensure clean writing
        System.out.println("=== Clearing card before writing ===");
        boolean clearSuccess = writeEmpty();
        if (!clearSuccess) {
            System.out.println("⚠️ Warning: Failed to clear card, but continuing with write...");
        } else {
            System.out.println("✓ Card cleared successfully");
        }
        
        String jsonHex = HexUtils.asciiToHex(json);
        int payloadLength = json.length();
        String payloadLengthHex = HexUtils.toHexString(payloadLength);
        
        System.out.println("JSON hex: " + jsonHex);
        System.out.println("Payload length: " + payloadLength);
        System.out.println("Payload length hex: " + payloadLengthHex);
        
        if (payloadLength > 255) {
            payloadLengthHex = HexUtils.decimalToFourBytesHex(payloadLength);
            System.out.println("Using 4-byte length format: " + payloadLengthHex);
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

        System.out.println("=== NDEF Record Header ===");
        System.out.println("Message Begin: " + messageBegin);
        System.out.println("Message End: " + messageEnd);
        System.out.println("Chunk Flag: " + chunkFlag);
        System.out.println("Short Record: " + shortRecord);
        System.out.println("ID Length: " + idLength);
        System.out.println("Type Name Format: " + typeNameFormat + " (Media Type)");
        System.out.println("Record Header Binary: " + recordHeaderBin);
        System.out.println("Record Header Hex: " + recordHeader);
        System.out.println("Type Length: " + typeLength);
        System.out.println("Type Field: " + typeField + " (application/json)");

        StringBuilder blockDataBuilder = new StringBuilder();
        blockDataBuilder.append(recordHeader);
        blockDataBuilder.append(typeLength);
        blockDataBuilder.append(payloadLengthHex);
        blockDataBuilder.append(typeField);
        blockDataBuilder.append(jsonHex);

        System.out.println("=== Final NDEF Block Data ===");
        System.out.println("Record Header: " + recordHeader);
        System.out.println("Type Length: " + typeLength);
        System.out.println("Payload Length: " + payloadLengthHex);
        System.out.println("Type Field: " + typeField);
        System.out.println("JSON Content: " + jsonHex);
        System.out.println("Complete Block Data: " + blockDataBuilder.toString());
        System.out.println("Block Data Length (bytes): " + (blockDataBuilder.length() / 2));

        System.out.println("=== Calling write() method ===");
        boolean result = write(blockDataBuilder);
        System.out.println("=== writeJsonToCard completed ===");
        System.out.println("Result: " + (result ? "SUCCESS" : "FAILED"));
        
        return result;
    }

    private boolean writeTextToCard(String text) throws ReaderException {
        System.out.println("=== Starting writeTextToCard ===");
        System.out.println("Input text: " + text);
        System.out.println("Text length: " + text.length());
        System.out.println("Timestamp: " + System.currentTimeMillis());

        // Build NDEF Text Record (as before)
        String prefixHex = "02656E"; // 2en
        String textHex = HexUtils.asciiToHex(text);
        int payloadLength = text.length() + 3;
        String payloadLengthHex = HexUtils.toHexString(payloadLength);
        if (payloadLength > 255) {
            payloadLengthHex = HexUtils.decimalToFourBytesHex(payloadLength);
        }
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
        // Build TLV
        String tlvPadding = "0000";
        String tlvNdefMessage = "03";
        int lengthBlockData = blockDataBuilder.length() / 2;
        String ndefMessageLength = lengthBlockData > 255 ? "FF" + HexUtils.decimalToTwoBytesHex(lengthBlockData) : HexUtils.toHexString(lengthBlockData);
        StringBuilder ndefBuilder = new StringBuilder();
        ndefBuilder.append(tlvPadding);
        ndefBuilder.append(tlvNdefMessage);
        ndefBuilder.append(ndefMessageLength);
        ndefBuilder.append(blockDataBuilder.toString());
        ndefBuilder.append("FE");
        // Convert to byte array
        String ndefHex = ndefBuilder.toString();
        int ndefLen = ndefHex.length();
        byte[] ndefBytes = new byte[(ndefLen + 1) / 2];
        for (int i = 0; i < ndefLen; i += 2) {
            ndefBytes[i / 2] = (byte) Integer.parseInt(ndefHex.substring(i, Math.min(i + 2, ndefLen)), 16);
        }
        // Write to NTAG21x
        return writeNdefToNtag21x(ndefBytes);
    }

    private boolean writeEmpty() throws ReaderException {
        boolean success = true;
        
        // For NTAG213 cards, clear NDEF data by writing zeros to writable blocks
        System.out.println("Clearing NDEF data on NTAG213 card...");
        
        for (int blockNumber = 5; blockNumber <= 35; blockNumber++) {
            // Skip protected blocks
            if (!isBlockWritable(blockNumber)) {
                System.out.println("Skipping protected block " + blockNumber + " during clear");
                continue;
            }
            
            int slotNum = 0;
            byte[] command;
            byte[] response = new byte[65538];
            int responseLength;

            // Write 16 bytes of zeros to clear the block
            String clearData = "00000000000000000000000000000000";
            command = HexUtils.toByteArray(MifareCommand.updateBlockCommand(blockNumber, clearData));
            System.out.println("Clearing block " + blockNumber + ": " + clearData);
            
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

            System.out.println("Clear block " + blockNumber + " response: " + bufferString.toString());

            if (hexResults.size() > 1 &&
                    hexResults.get(hexResults.size() - 2).equals("90") &&
                    hexResults.get(hexResults.size() - 1).equals("00")
            ) {
                System.out.println("Clear block " + blockNumber + " successful");
            } else {
                System.out.println("Error clearing block " + blockNumber + " (may be protected)");
                // Don't break on protected blocks, just continue
                continue;
            }
        }

        System.out.println("NTAG213 card clearing completed");
        return true; // Return true even if some blocks couldn't be cleared (they might be protected)
    }

    private boolean isBlockWritable(int blockNumber) {
        // NTAG213 has 36 pages (0-35)
        // Blocks 0-3 are typically read-only (UID, internal bytes, lock bytes)
        // Block 4 might be protected depending on the card configuration
        // Blocks 5-35 are typically writable
        return blockNumber >= 5 && blockNumber <= 35;
    }

    private boolean write(StringBuilder blockDataBuilder) throws ReaderException {
        boolean success = true;

        System.out.println("=== Starting write() method for NTAG213 ===");
        System.out.println("Block data builder: " + blockDataBuilder.toString());
        int lengthBlockData = blockDataBuilder.length() / 2;
        System.out.println("Length block data (bytes): " + lengthBlockData);

        String ndefMessageLength;
        String tlvPadding = "0000";
        String tlvNdefMessage = "03";

        if (lengthBlockData > 255) {
            ndefMessageLength = "FF" + HexUtils.decimalToTwoBytesHex(lengthBlockData);
            System.out.println("Using extended length format (FF + 2 bytes): " + ndefMessageLength);
        } else {
            ndefMessageLength = HexUtils.toHexString(lengthBlockData);
            System.out.println("Using short length format: " + ndefMessageLength);
        }

        StringBuilder ndefBuilder = new StringBuilder();
        ndefBuilder.append(tlvPadding);
        ndefBuilder.append(tlvNdefMessage);
        ndefBuilder.append(ndefMessageLength);
        ndefBuilder.append(blockDataBuilder.toString());
        ndefBuilder.append("FE");

        System.out.println("=== TLV Structure ===");
        System.out.println("TLV Padding: " + tlvPadding);
        System.out.println("TLV NDEF Tag: " + tlvNdefMessage);
        System.out.println("NDEF Length: " + ndefMessageLength);
        System.out.println("NDEF Data: " + blockDataBuilder.toString());
        System.out.println("TLV Terminator: FE");
        System.out.println("Complete NDEF: " + ndefBuilder.toString());
        System.out.println("Total NDEF length (bytes): " + (ndefBuilder.length() / 2));

        // Split into 16-byte blocks (32 hex characters)
        List<String> ndefSplit = HexUtils.usingSplitMethod(ndefBuilder.toString(), 32);
        System.out.println("=== Block Splitting ===");
        System.out.println("Number of blocks needed: " + ndefSplit.size());
        for (int i = 0; i < ndefSplit.size(); i++) {
            System.out.println("Block " + i + ": " + ndefSplit.get(i) + " (length: " + (ndefSplit.get(i).length() / 2) + " bytes)");
        }

        int index = 0;
        int blockNumber = 5; // Start from block 5 for NTAG213 (blocks 0-4 are typically protected)

        System.out.println("=== Writing to NTAG213 Card ===");
        System.out.println("Starting block number: " + blockNumber);
        System.out.println("Maximum block number: 35 (NTAG213 has 36 pages, 0-35)");

        while (index < ndefSplit.size()) {
            if (blockNumber > 35) { // NTAG213 has 36 pages (0-35)
                System.out.println("Reached maximum block number for NTAG213");
                System.out.println("⚠️ Warning: Not all data could be written due to block limitations");
                break;
            }

            // Skip protected blocks
            if (!isBlockWritable(blockNumber)) {
                System.out.println("Skipping protected block " + blockNumber);
                blockNumber++;
                continue;
            }

            int slotNum = 0;
            byte[] command;
            byte[] response = new byte[65538];
            int responseLength;

            String blockData = HexUtils.paddingTo16Bytes(ndefSplit.get(index));
            System.out.println("Writing to block " + blockNumber + ": " + blockData);

            command = HexUtils.toByteArray(MifareCommand.updateBlockCommand(blockNumber, blockData));
            System.out.println("APDU Command: " + MifareCommand.updateBlockCommand(blockNumber, blockData));
            
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

            System.out.println("Write response: " + bufferString.toString());

            if (hexResults.size() > 1 &&
                    hexResults.get(hexResults.size() - 2).equals("90") &&
                    hexResults.get(hexResults.size() - 1).equals("00")
            ) {
                System.out.println("✓ Write to block " + blockNumber + " successful");
            } else {
                System.out.println("✗ Error writing to block " + blockNumber);
                System.out.println("Response: " + bufferString.toString());
                
                // Handle specific error codes
                if (bufferString.toString().equals("6300")) {
                    System.out.println("Error 6300: Command not allowed or block might be protected");
                    System.out.println("Block " + blockNumber + " appears to be protected. Trying next block...");
                    
                    // Skip this block and try the next one
                    blockNumber++;
                    continue;
                } else if (bufferString.toString().equals("6400")) {
                    System.out.println("Error 6400: Execution error");
                    System.out.println("✗ Execution error on block " + blockNumber);
                    success = false;
                    break;
                } else if (bufferString.toString().equals("6700")) {
                    System.out.println("Error 6700: Wrong length");
                    System.out.println("✗ Wrong length error on block " + blockNumber);
                    success = false;
                    break;
                } else {
                    System.out.println("✗ Unrecoverable error on block " + blockNumber + ": " + bufferString.toString());
                    success = false;
                    break;
                }
            }

            index++;
            blockNumber++;
        }

        System.out.println("=== write() method completed ===");
        System.out.println("Final result: " + (success ? "SUCCESS" : "FAILED"));
        System.out.println("Blocks written: " + index + " out of " + ndefSplit.size());

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
        System.out.println("ACRReader: Read NDEF Message from NTAG213");
        int blockNumber = 4; // Start from block 4 for NTAG213
        final int startingBlockNo = 4;
        int ndefMessageLength = 0;
        int paddingCount = 0;

        boolean loop = true;
        List<String> ndefMessage = new ArrayList<>();

        while (loop) {
            if (blockNumber > 35) { // NTAG213 has 36 pages (0-35)
                break;
            }

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

            blockNumber++;
        }

        System.out.println("All NDEF Message " + ndefMessage.toString());

        List<NdefBlock> blocks = new ArrayList<>();
        final int messageByteLength = ((ndefMessageLength - (startingBlockNo - 1)) * 4) - paddingCount;
        
        // Add bounds checking to prevent IndexOutOfBoundsException
        final int actualMessageLength = Math.min(messageByteLength, ndefMessage.size());
        System.out.println("ACRReader: Calculated message length: " + messageByteLength);
        System.out.println("ACRReader: Actual message length: " + actualMessageLength);
        System.out.println("ACRReader: NDEF message size: " + ndefMessage.size());
        
        for (int k = 0; k < actualMessageLength; k++) {
            // find first message
            
            System.out.println("ACRReader: Processing at index " + k);
            
            // Ensure we don't exceed the list bounds
            if (k >= ndefMessage.size()) {
                System.out.println("ACRReader: Index " + k + " exceeds message size, breaking");
                break;
            }
            
            int remainingLength = ndefMessage.size() - k;
            int blockIndex = NdefBlockUtil.getBlockIndex(ndefMessage.subList(k, ndefMessage.size()));
            System.out.println("NILAI K " + k);
            System.out.println("BLOCK INDEX " + blockIndex);
            System.out.println("REMAINING LENGTH " + remainingLength);
            
            if (blockIndex < 0) {
                // End of message
                int endIndex = Math.min(k + (blockIndex * -1), ndefMessage.size());
                System.out.println("ACRReader: End of message detected, creating block from " + k + " to " + endIndex);
                NdefBlock block = new NdefBlock(ndefMessage.subList(k, endIndex));
                if (!block.isEmptyRecord()) {
                    blocks.add(block);
                }
                break;
            } else if (blockIndex > 0) {
                // Valid block found
                int endIndex = Math.min(k + blockIndex, ndefMessage.size());
                System.out.println("ACRReader: Creating block from " + k + " to " + endIndex);
                NdefBlock block = new NdefBlock(ndefMessage.subList(k, endIndex));
                if (!block.isEmptyRecord()) {
                    blocks.add(block);
                }
                k = k + blockIndex - 1;
            } else {
                // Invalid block index, skip this byte
                System.out.println("ACRReader: Invalid block index, skipping byte at " + k);
            }
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

    /**
     * Write 4 bytes to a specific page of NTAG21x using ACS Direct Transmit.
     * @param page The page number (e.g., 4 for first user page)
     * @param data 4 bytes to write (must be length 4)
     * @return true if write was successful, false otherwise
     */
    private boolean writeNtag21xPage(int page, byte[] data) throws ReaderException {
        if (data.length != 4) throw new IllegalArgumentException("Data must be 4 bytes");
        int slotNum = 0;
        byte[] command = new byte[] {
            (byte)0xFF, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x05,
            (byte)0xD4, (byte)0x40, (byte)0x01, // InDataExchange, Card number
            (byte)0xA2, // NTAG21x WRITE command
            (byte)page, // Page number
            data[0], data[1], data[2], data[3]
        };
        byte[] response = new byte[256];
        int responseLength = getReader().transmit(slotNum, command, command.length, response, response.length);
        if (responseLength >= 2 && response[responseLength-2] == (byte)0x90 && response[responseLength-1] == (byte)0x00) {
            System.out.println("Write to page " + page + " successful");
            return true;
        } else {
            System.out.println("Write to page " + page + " failed, response: " + HexUtils.toHexString(response));
            return false;
        }
    }

    /**
     * Write a full NDEF TLV byte array to NTAG21x user memory (starting at page 4).
     */
    private boolean writeNdefToNtag21x(byte[] ndefTlv) throws ReaderException {
        int page = 4; // NTAG21x user memory starts at page 4
        for (int i = 0; i < ndefTlv.length; i += 4) {
            byte[] chunk = new byte[4];
            for (int j = 0; j < 4; j++) {
                if (i + j < ndefTlv.length) {
                    chunk[j] = ndefTlv[i + j];
                } else {
                    chunk[j] = 0x00; // pad with zeros
                }
            }
            boolean ok = writeNtag21xPage(page, chunk);
            if (!ok) return false;
            page++;
        }
        return true;
    }
}


