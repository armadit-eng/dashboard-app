enum HillGrade { flat, uphillMild, uphillSteep, downhill }

enum MapStyleOption {
  osmStandard('osm', 'OSM Standar'),
  darkCarto('dark', 'Cockpit Gelap'),
  satellite('sat', 'Satelit Esri'),
  topo('topo', 'Topografi');

  const MapStyleOption(this.id, this.label);
  final String id;
  final String label;

  static MapStyleOption fromId(String? id) {
    return MapStyleOption.values.firstWhere(
      (e) => e.id == id,
      orElse: () => MapStyleOption.darkCarto,
    );
  }
}

class GeoPointX {
  final double lat;
  final double lng;
  final double? altM;

  const GeoPointX(this.lat, this.lng, {this.altM});

  Map<String, dynamic> toJson() => {
        'lat': lat,
        'lng': lng,
        if (altM != null) 'altM': altM,
      };

  factory GeoPointX.fromJson(Map<String, dynamic> m) => GeoPointX(
        (m['lat'] as num).toDouble(),
        (m['lng'] as num).toDouble(),
        altM: (m['altM'] as num?)?.toDouble(),
      );
}

class GpsState {
  final GeoPointX? location;
  final double? speedKmh;
  final double? headingDeg;
  final double? accuracyM;
  final String roadName;
  final String areaName;
  final bool isFromCache;

  const GpsState({
    this.location,
    this.speedKmh,
    this.headingDeg,
    this.accuracyM,
    this.roadName = '',
    this.areaName = '',
    this.isFromCache = false,
  });

  GpsState copyWith({
    GeoPointX? location,
    double? speedKmh,
    double? headingDeg,
    double? accuracyM,
    String? roadName,
    String? areaName,
    bool? isFromCache,
  }) {
    return GpsState(
      location: location ?? this.location,
      speedKmh: speedKmh ?? this.speedKmh,
      headingDeg: headingDeg ?? this.headingDeg,
      accuracyM: accuracyM ?? this.accuracyM,
      roadName: roadName ?? this.roadName,
      areaName: areaName ?? this.areaName,
      isFromCache: isFromCache ?? this.isFromCache,
    );
  }
}

class RouteStepX {
  final String instruction;
  final String roadName;
  final double distanceM;
  final double durationSec;
  final String maneuverType;
  final String? modifier;
  final GeoPointX location;

  const RouteStepX({
    required this.instruction,
    required this.roadName,
    required this.distanceM,
    required this.durationSec,
    required this.maneuverType,
    this.modifier,
    required this.location,
  });
}

class ElevationSample {
  final double distanceKm;
  final double elevationM;
  final double gradePct;
  final HillGrade gradeType;
  final GeoPointX point;

  const ElevationSample({
    required this.distanceKm,
    required this.elevationM,
    required this.gradePct,
    required this.gradeType,
    required this.point,
  });
}

class RouteEnergyEstimate {
  final double totalWh;
  final double regenWh;
  final double netWh;
  final double whPerKm;
  final double socUsedPct;
  final double? socAtArrivalPct;
  final double maxClimbPct;
  final double totalAscentM;
  final double totalDescentM;

  const RouteEnergyEstimate({
    required this.totalWh,
    required this.regenWh,
    required this.netWh,
    required this.whPerKm,
    required this.socUsedPct,
    required this.socAtArrivalPct,
    required this.maxClimbPct,
    required this.totalAscentM,
    required this.totalDescentM,
  });
}

class WaypointArrivalSummary {
  final String label;
  final double legDistKm;
  final double legEtaMin;
  final double? arrivalSocPct;
  final bool isChargingStop;

  const WaypointArrivalSummary({
    required this.label,
    required this.legDistKm,
    required this.legEtaMin,
    required this.arrivalSocPct,
    this.isChargingStop = false,
  });
}

class NavRouteX {
  final List<GeoPointX> geometry;
  final List<RouteStepX> steps;
  final double distanceM;
  final double durationSec;
  final List<ElevationSample> elevationSamples;
  final RouteEnergyEstimate? energyEstimate;
  final List<WaypointArrivalSummary> legSummaries;

  const NavRouteX({
    required this.geometry,
    required this.steps,
    required this.distanceM,
    required this.durationSec,
    this.elevationSamples = const [],
    this.energyEstimate,
    this.legSummaries = const [],
  });
}

class PlannedStop {
  final String id;
  final String label;
  final double lat;
  final double lng;
  final bool isSpklu;

  const PlannedStop({
    required this.id,
    required this.label,
    required this.lat,
    required this.lng,
    this.isSpklu = false,
  });

  Map<String, dynamic> toJson() => {
        'id': id,
        'label': label,
        'lat': lat,
        'lng': lng,
        'isSpklu': isSpklu,
      };

  factory PlannedStop.fromJson(Map<String, dynamic> m) => PlannedStop(
        id: m['id']?.toString() ?? '',
        label: m['label']?.toString() ?? 'Stop',
        lat: (m['lat'] as num).toDouble(),
        lng: (m['lng'] as num).toDouble(),
        isSpklu: m['isSpklu'] == true,
      );
}

class SavedPlace {
  final String id;
  final String name;
  final String category;
  final double lat;
  final double lng;
  final int createdAtMs;

  const SavedPlace({
    required this.id,
    required this.name,
    required this.category,
    required this.lat,
    required this.lng,
    required this.createdAtMs,
  });

  Map<String, dynamic> toJson() => {
        'id': id,
        'name': name,
        'category': category,
        'lat': lat,
        'lng': lng,
        'createdAtMs': createdAtMs,
      };

  factory SavedPlace.fromJson(Map<String, dynamic> m) => SavedPlace(
        id: m['id']?.toString() ?? '',
        name: m['name']?.toString() ?? 'Lokasi',
        category: m['category']?.toString() ?? 'Umum',
        lat: (m['lat'] as num).toDouble(),
        lng: (m['lng'] as num).toDouble(),
        createdAtMs: (m['createdAtMs'] as num?)?.toInt() ?? 0,
      );
}

class SpkluStation {
  final String id;
  final String name;
  final String operatorName;
  final double lat;
  final double lng;
  final String socketType;
  final double? powerKw;
  final bool isUserAdded;
  final double? distanceKm;

  const SpkluStation({
    required this.id,
    required this.name,
    required this.operatorName,
    required this.lat,
    required this.lng,
    this.socketType = 'Type 2 / CCS2',
    this.powerKw,
    this.isUserAdded = false,
    this.distanceKm,
  });

  SpkluStation withDistance(double distKm) => SpkluStation(
        id: id,
        name: name,
        operatorName: operatorName,
        lat: lat,
        lng: lng,
        socketType: socketType,
        powerKw: powerKw,
        isUserAdded: isUserAdded,
        distanceKm: distKm,
      );

  Map<String, dynamic> toJson() => {
        'id': id,
        'name': name,
        'operatorName': operatorName,
        'lat': lat,
        'lng': lng,
        'socketType': socketType,
        'powerKw': powerKw,
        'isUserAdded': isUserAdded,
      };

  factory SpkluStation.fromJson(Map<String, dynamic> m) => SpkluStation(
        id: m['id']?.toString() ?? '',
        name: m['name']?.toString() ?? 'SPKLU',
        operatorName: m['operatorName']?.toString() ?? 'PLN / Mitra',
        lat: (m['lat'] as num).toDouble(),
        lng: (m['lng'] as num).toDouble(),
        socketType: m['socketType']?.toString() ?? 'AC/DC',
        powerKw: (m['powerKw'] as num?)?.toDouble(),
        isUserAdded: m['isUserAdded'] == true,
      );
}

class FieldNotePin {
  final String id;
  final String name;
  final String category;
  final String note;
  final double lat;
  final double lng;
  final int timestampMs;

  const FieldNotePin({
    required this.id,
    required this.name,
    required this.category,
    required this.note,
    required this.lat,
    required this.lng,
    required this.timestampMs,
  });

  Map<String, dynamic> toJson() => {
        'id': id,
        'name': name,
        'category': category,
        'note': note,
        'lat': lat,
        'lng': lng,
        'timestampMs': timestampMs,
      };

  factory FieldNotePin.fromJson(Map<String, dynamic> m) => FieldNotePin(
        id: m['id']?.toString() ?? '',
        name: m['name']?.toString() ?? 'Catatan',
        category: m['category']?.toString() ?? 'SPKLU',
        note: m['note']?.toString() ?? '',
        lat: (m['lat'] as num).toDouble(),
        lng: (m['lng'] as num).toDouble(),
        timestampMs: (m['timestampMs'] as num?)?.toInt() ?? 0,
      );
}

class WeatherData {
  final double tempC;
  final double feelsLikeC;
  final int humidityPct;
  final double windKmh;
  final int rainProbPct;
  final double? uvIndex;
  final int weatherCode;
  final String description;
  final String moodTag;
  final String sunrise;
  final String sunset;
  final int fetchedAtMs;

  const WeatherData({
    required this.tempC,
    required this.feelsLikeC,
    required this.humidityPct,
    required this.windKmh,
    required this.rainProbPct,
    this.uvIndex,
    required this.weatherCode,
    required this.description,
    required this.moodTag,
    this.sunrise = '--:--',
    this.sunset = '--:--',
    required this.fetchedAtMs,
  });
}
