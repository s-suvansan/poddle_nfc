import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'poddle_nfc_platform_interface.dart';

/// An implementation of [PoddleNfcPlatform] that uses method channels.
class MethodChannelPoddleNfc extends PoddleNfcPlatform {
  /// The method channel used to interact with the native platform.
  @visibleForTesting
  final methodChannel = const MethodChannel('poddle_nfc');

  @override
  Future<String?> getPlatformVersion() async {
    final version = await methodChannel.invokeMethod<bool>('getPlatformVersion');
    print(version.toString());
    return version.toString();
  }
}
