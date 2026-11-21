import 'platform_tools_channel.dart';

class VoiceAlertService {
  int _lastSpokenMs = 0;

  Future<void> speak(String text, {bool force = false}) async {
    final now = DateTime.now().millisecondsSinceEpoch;
    if (!force && now - _lastSpokenMs < 4000) return;
    _lastSpokenMs = now;
    await PlatformToolsChannel.speakTts(text);
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
