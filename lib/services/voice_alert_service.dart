import 'package:flutter_tts/flutter_tts.dart';

class VoiceAlertService {
  final FlutterTts _tts = FlutterTts();
  bool _initialized = false;
  int _lastSpokenMs = 0;

  Future<void> init() async {
    if (_initialized) return;
    _initialized = true;
    try {
      await _tts.setLanguage('id-ID');
      await _tts.setSpeechRate(0.5);
      await _tts.setVolume(1.0);
      await _tts.setPitch(1.0);
    } catch (_) {}
  }

  Future<void> speak(String text, {bool force = false}) async {
    final now = DateTime.now().millisecondsSinceEpoch;
    if (!force && now - _lastSpokenMs < 4000) return;
    _lastSpokenMs = now;
    await init();
    try {
      await _tts.speak(text);
    } catch (_) {}
  }

  static int navVoiceCodeForManeuver(String type, String? modifier) {
    if (type == 'arrive') return 14;
    if (type == 'roundabout' || type == 'rotary') return 12;
    switch (modifier) {
      case 'left':
      case 'sharp left':
        return 8;
      case 'slight left':
        return 10;
      case 'right':
      case 'sharp right':
        return 9;
      case 'slight right':
        return 11;
      case 'uturn':
        return 13;
      default:
        return 7;
    }
  }
}
