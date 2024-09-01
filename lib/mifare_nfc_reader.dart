import 'package:flutter/services.dart';

class MifareNfcReader {
  static const MethodChannel _channel = MethodChannel('mifare_nfc_reader');

  static Future<bool> init () async {
    return await _channel.invokeMethod('init');
  }

  static Future<bool> writeText(String text) async {
    return await _channel.invokeMethod('writeText', {'text': text});
  }

  static Future<bool> writeJson(String json) async {
    return await _channel.invokeMethod('writeJson', {'json': json});
  }

  static Future<bool> clearCard() async {
    return await _channel.invokeMethod('clearCard');
  }
}
