package com.samelement.mifare_nfc_reader;

public class MifareCommand {
    public static final String GET_UID_COMMAND = "FFCA000004";
    public static final String LOAD_AUTH_KEY_COMMAND = "FF82000006FFFFFFFFFFFF";

    public static String getAuthenticationKeyBCommand(int blockNumber) {
        String hexBlockNumber = HexUtils.toHexString(blockNumber);

        return "FF860000050100" + hexBlockNumber + "6000";
    }

    public static String getAuthenticationKeyACommand(int blockNumber) {
        String hexBlockNumber = HexUtils.toHexString(blockNumber);

        return "FF8800" + hexBlockNumber + "6000";
    }

    public static String readDataBlockCommand(int blockNumber) {
        String hexBlockNumber = HexUtils.toHexString(blockNumber);

        return "FFB000" + hexBlockNumber + "04";
    }

    public static String updateBlockCommand(int blockNumber, String data) {
        String hexBlockNumber = HexUtils.toHexString(blockNumber);
        String hexDataLength = HexUtils.toHexString(data.length()/2);
        String command = "FFD600" + hexBlockNumber + hexDataLength + data;
        System.out.println("update command "+command);

        return command;
    }

    public static String clearCommand() {
        return "D8000000";
    }

}
