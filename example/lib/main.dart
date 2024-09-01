import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:mifare_nfc_reader/mifare_nfc_reader.dart';

const channelName = "mifare_nfc_reader";
const methodChannel = MethodChannel(channelName);

void main() {
  WidgetsFlutterBinding.ensureInitialized();

  runApp(const MyApp());
}

class MyApp extends StatefulWidget {
  const MyApp({Key? key}) : super(key: key);

  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  String _state = "No reader attached";
  List<String> _ndefMessages = [];
  String _uid = "";

  @override
  void initState() {
    super.initState();

    methodChannel.setMethodCallHandler(onFlutterCall);

    MifareNfcReader.init();
  }

  Future<void> onFlutterCall(MethodCall call) async {
    switch (call.method) {
      case "onReceiveNdefMessages":
        final List<dynamic> result = call.arguments;
        setState(() {
          _ndefMessages.addAll(result.map((e) => e.toString()).toList());
        });
        break;
      case "onCardStateChanged":
        if (call.arguments.toString().toLowerCase() == "present") {
          setState(() {
            _state = "The card is present";
          });
        }
        else {
          resetOnDisconnectedOrMissingCard("The card is absent or unknown");
        }
        break;
      case "onUsbDeviceStateChanged":
        if (call.arguments.toString().toLowerCase() == "attached") {
          setState(() {
            _state = "The reader is attached";
          });
        }
        else {
          resetOnDisconnectedOrMissingCard("The reader is detached");
        }
        break;
      case "onReadCardError":
        resetOnDisconnectedOrMissingCard(call.arguments.toString());
        break;
      case "onReadUID":
        setState(() {
          _uid = call.arguments.toString();
        });
        break;
    }
  }

  void resetOnDisconnectedOrMissingCard(String state) {
    setState(() {
      _uid = "";
      _ndefMessages.clear();
      _state = state;
    });
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(
          title: const Text('ACR122U Reader App'),
        ),
        body: Center(
            child: Column(
          children: [
            TextButton(onPressed: () {
              MifareNfcReader.writeJson('{"id":"4583C59D.1078.L.20220301.SUMERI","name":"HANIF","gender":"L","birthDate":"2022-03-01","mother":"SUMERI","registeredDate":"2022-03-20 13:55:43.000"}');
            }, child: const Text("Write")),
            Text("Status : $_state"),
            Text("UID : $_uid"),
            TextButton(onPressed: () {
              MifareNfcReader.clearCard();
            }, child: const Text("Clear data"),),
            Expanded(
              child: Column(
                children: _ndefMessages.map((e) => Text(e)).toList(),
              )
            ),
          ],
        )),
      ),
    );
  }
}