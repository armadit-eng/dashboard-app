import 'dart:convert';
import 'dart:math' as math;

import 'package:http/http.dart' as http;

import '../models/app_models.dart';
import '../models/navigation_models.dart';
import 'route_physics.dart';

class RemoteApisService {
  static final http.Client _client = http.Client();
  static const Map<String, String> _headers = {
    'User-Agent': 'DashboardAppEV/1.0 (com.dashboard.app.dashboard_app)',
  };

  static Future<WeatherData?> fetchWeather(double lat, double lng) async {
    try {
      final url = Uri.parse(
        'https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lng'
        '&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m'
        '&hourly=precipitation_probability,uv_index&daily=sunrise,sunset&timezone=auto&forecast_days=1',
      );
      final res = await _client
          .get(url, headers: _headers)
          .timeout(const Duration(seconds: 10));
      if (res.statusCode != 200) return null;
      final j = jsonDecode(res.body) as Map<String, dynamic>;
      final cur = j['current'] as Map<String, dynamic>?;
      if (cur == null) return null;

      final hourly = j['hourly'] as Map<String, dynamic>?;
      final probs = (hourly?['precipitation_probability'] as List?) ?? const [];
      final uvs = (hourly?['uv_index'] as List?) ?? const [];
      final hourIdx = DateTime.now()
          .hour
          .clamp(0, math.max(0, probs.length - 1))
          .toInt();
      final rainProb =
          probs.isNotEmpty ? (probs[hourIdx] as num?)?.toInt() ?? 0 : 0;
      final uvIdx = uvs.isNotEmpty && hourIdx < uvs.length
          ? (uvs[hourIdx] as num?)?.toDouble()
          : null;

      final daily = j['daily'] as Map<String, dynamic>?;
      String sunrise = '--:--';
      String sunset = '--:--';
      final srList = (daily?['sunrise'] as List?) ?? const [];
      final ssList = (daily?['sunset'] as List?) ?? const [];
      if (srList.isNotEmpty) {
        final s = srList.first.toString();
        if (s.contains('T')) sunrise = s.split('T').last;
      }
      if (ssList.isNotEmpty) {
        final s = ssList.first.toString();
        if (s.contains('T')) sunset = s.split('T').last;
      }

      final wCode = (cur['weather_code'] as num?)?.toInt() ?? 1;
      final decoded = RoutePhysics.decodeWeatherCode(wCode);

      return WeatherData(
        tempC: (cur['temperature_2m'] as num?)?.toDouble() ?? 28.0,
        feelsLikeC: (cur['apparent_temperature'] as num?)?.toDouble() ?? 30.0,
        humidityPct: (cur['relative_humidity_2m'] as num?)?.toInt() ?? 70,
        windKmh: (cur['wind_speed_10m'] as num?)?.toDouble() ?? 8.0,
        rainProbPct: rainProb,
        uvIndex: uvIdx,
        weatherCode: wCode,
        description: decoded.desc,
        moodTag: decoded.moodTag,
        sunrise: sunrise,
        sunset: sunset,
        fetchedAtMs: DateTime.now().millisecondsSinceEpoch,
      );
    } catch (_) {
      return null;
    }
  }

  static Future<({String road, String area})?> reverseGeocode(
      double lat, double lng) async {
    try {
      final url = Uri.parse(
        'https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=$lat&lon=$lng&zoom=18&addressdetails=1',
      );
      final res = await _client
          .get(url, headers: _headers)
          .timeout(const Duration(seconds: 8));
      if (res.statusCode != 200) return null;
      final j = jsonDecode(res.body) as Map<String, dynamic>;
      final addr = (j['address'] as Map<String, dynamic>?) ?? {};
      final road = addr['road']?.toString() ??
          addr['pedestrian']?.toString() ??
          addr['suburb']?.toString() ??
          j['name']?.toString() ??
          '';
      final area = addr['city_district']?.toString() ??
          addr['suburb']?.toString() ??
          addr['village']?.toString() ??
          addr['city']?.toString() ??
          addr['town']?.toString() ??
          '';
      return (road: road, area: area);
    } catch (_) {
      return null;
    }
  }

  static Future<List<SavedPlace>> searchPlaces(String query) async {
    if (query.trim().isEmpty) return const [];
    final direct = RoutePhysics.parseCoordinatesOrGoogleMapsUrl(query);
    if (direct != null) {
      return [
        SavedPlace(
          id: 'coord_${direct.lat}_${direct.lng}',
          name:
              'Koordinat (${direct.lat.toStringAsFixed(5)}, ${direct.lng.toStringAsFixed(5)})',
          category: 'Pin Koordinat',
          lat: direct.lat,
          lng: direct.lng,
          createdAtMs: DateTime.now().millisecondsSinceEpoch,
        ),
      ];
    }

    try {
      final encoded = Uri.encodeQueryComponent(query.trim());
      final url = Uri.parse(
        'https://nominatim.openstreetmap.org/search?format=jsonv2&q=$encoded&limit=8&addressdetails=1',
      );
      final res = await _client
          .get(url, headers: _headers)
          .timeout(const Duration(seconds: 9));
      if (res.statusCode != 200) return const [];
      final arr = jsonDecode(res.body) as List<dynamic>;
      return arr.whereType<Map<String, dynamic>>().map((item) {
        final lat = double.tryParse(item['lat']?.toString() ?? '') ?? 0;
        final lng = double.tryParse(item['lon']?.toString() ?? '') ?? 0;
        final disp = item['display_name']?.toString() ?? 'Lokasi';
        final shortName = disp.split(',').take(2).join(', ').trim();
        return SavedPlace(
          id: '${item['place_id'] ?? disp.hashCode}',
          name: shortName.isNotEmpty ? shortName : disp,
          category: item['type']?.toString() ?? 'Hasil Cari',
          lat: lat,
          lng: lng,
          createdAtMs: DateTime.now().millisecondsSinceEpoch,
        );
      }).toList();
    } catch (_) {
      return const [];
    }
  }

  static Future<List<double>?> fetchElevations(List<GeoPointX> points) async {
    if (points.isEmpty) return const [];
    try {
      final lats = points.map((p) => p.lat.toStringAsFixed(5)).join(',');
      final lngs = points.map((p) => p.lng.toStringAsFixed(5)).join(',');
      final url = Uri.parse(
        'https://api.open-meteo.com/v1/elevation?latitude=$lats&longitude=$lngs',
      );
      final res = await _client
          .get(url, headers: _headers)
          .timeout(const Duration(seconds: 9));
      if (res.statusCode != 200) return null;
      final j = jsonDecode(res.body) as Map<String, dynamic>;
      final elev = j['elevation'] as List<dynamic>?;
      if (elev == null) return null;
      return elev.map((e) => (e as num).toDouble()).toList();
    } catch (_) {
      return null;
    }
  }

  static Future<NavRouteX?> fetchOsrmRoute({
    required GeoPointX origin,
    required List<PlannedStop> stops,
    required AppSettings settings,
    required double? currentSocPct,
  }) async {
    if (stops.isEmpty) return null;
    final allPts = [
      origin,
      ...stops.map((s) => GeoPointX(s.lat, s.lng)),
    ];
    final coordStr =
        allPts.map((p) => '${p.lng.toStringAsFixed(6)},${p.lat.toStringAsFixed(6)}').join(';');

    try {
      final url = Uri.parse(
        'https://router.project-osrm.org/route/v1/driving/$coordStr?overview=full&geometries=geojson&steps=true',
      );
      final res = await _client
          .get(url, headers: _headers)
          .timeout(const Duration(seconds: 12));
      if (res.statusCode != 200) return null;

      final j = jsonDecode(res.body) as Map<String, dynamic>;
      final routes = j['routes'] as List<dynamic>?;
      if (routes == null || routes.isEmpty) return null;
      final route0 = routes.first as Map<String, dynamic>;

      final distM = (route0['distance'] as num?)?.toDouble() ?? 0.0;
      final durSec = (route0['duration'] as num?)?.toDouble() ?? 0.0;

      final geomObj = route0['geometry'] as Map<String, dynamic>?;
      final rawCoords = (geomObj?['coordinates'] as List<dynamic>?) ?? const [];
      final geometry = rawCoords.whereType<List>().map((c) {
        final lng = (c[0] as num).toDouble();
        final lat = (c[1] as num).toDouble();
        return GeoPointX(lat, lng);
      }).toList();

      final steps = <RouteStepX>[];
      final legSummaries = <WaypointArrivalSummary>[];
      final legs = (route0['legs'] as List<dynamic>?) ?? const [];

      final totalPackWh =
          settings.b1NominalV * settings.b1CapacityAh +
              (settings.savedEsp2Address != null
                  ? settings.b2NominalV * settings.b2CapacityAh
                  : 0.0);

      double runningSoc = currentSocPct ?? 100.0;
      for (int i = 0; i < legs.length; i++) {
        final leg = legs[i] as Map<String, dynamic>;
        final legDistKm = ((leg['distance'] as num?)?.toDouble() ?? 0.0) / 1000.0;
        final legDurMin = ((leg['duration'] as num?)?.toDouble() ?? 0.0) / 60.0;
        final estWh = legDistKm * settings.refWhPerKm * settings.routeCalFactor;
        final socDrop = totalPackWh > 0 ? (estWh / totalPackWh) * 100.0 : 0.0;
        runningSoc = (runningSoc - socDrop).clamp(0.0, 100.0);

        final stopMeta = i < stops.length ? stops[i] : null;
        legSummaries.add(WaypointArrivalSummary(
          label: stopMeta?.label ?? 'Tujuan ${i + 1}',
          legDistKm: legDistKm,
          legEtaMin: legDurMin,
          arrivalSocPct: currentSocPct != null ? runningSoc : null,
          isChargingStop: stopMeta?.isSpklu ?? false,
        ));
        if (stopMeta?.isSpklu == true && currentSocPct != null) {
          runningSoc = math.max(runningSoc, 85.0);
        }

        final legSteps = (leg['steps'] as List<dynamic>?) ?? const [];
        for (final stRaw in legSteps) {
          final st = stRaw as Map<String, dynamic>;
          final man = (st['maneuver'] as Map<String, dynamic>?) ?? {};
          final locArr = (man['location'] as List<dynamic>?) ?? const [];
          if (locArr.length < 2) continue;
          final roadName = st['name']?.toString() ?? '';
          final manType = man['type']?.toString() ?? 'continue';
          final modifier = man['modifier']?.toString();
          steps.add(RouteStepX(
            instruction:
                RoutePhysics.translateOsrmManeuver(manType, modifier, roadName),
            roadName: roadName,
            distanceM: (st['distance'] as num?)?.toDouble() ?? 0.0,
            durationSec: (st['duration'] as num?)?.toDouble() ?? 0.0,
            maneuverType: manType,
            modifier: modifier,
            location: GeoPointX(
                (locArr[1] as num).toDouble(), (locArr[0] as num).toDouble()),
          ));
        }
      }

      final downsampled = RoutePhysics.downsamplePoints(geometry, maxSamples: 36);
      final elevations = await fetchElevations(downsampled) ?? const <double>[];
      final physics = RoutePhysics.buildElevationAndEnergy(
        sampledPoints: downsampled,
        elevations: elevations,
        totalDistanceKm: distM / 1000.0,
        durationMin: durSec / 60.0,
        bikeKg: settings.bikeWeightKg,
        riderKg: settings.riderWeightKg,
        loadKg: settings.loadWeightKg,
        calFactor: settings.routeCalFactor,
        totalPackWh: totalPackWh,
        currentSocPct: currentSocPct,
      );

      return NavRouteX(
        geometry: geometry,
        steps: steps,
        distanceM: distM,
        durationSec: durSec,
        elevationSamples: physics.samples,
        energyEstimate: physics.estimate,
        legSummaries: legSummaries,
      );
    } catch (_) {
      return null;
    }
  }

  static Future<List<SpkluStation>> fetchNearbySpklu({
    required double lat,
    required double lng,
    int radiusMeters = 15000,
    String ocmApiKey = '',
  }) async {
    final results = <String, SpkluStation>{};

    // 1. Overpass OSM Query
    try {
      final query =
          '[out:json][timeout:12];(node["amenity"="charging_station"](around:$radiusMeters,$lat,$lng);way["amenity"="charging_station"](around:$radiusMeters,$lat,$lng););out center 30;';
      final res = await _client
          .post(
            Uri.parse('https://overpass-api.de/api/interpreter'),
            headers: _headers,
            body: {'data': query},
          )
          .timeout(const Duration(seconds: 12));
      if (res.statusCode == 200) {
        final j = jsonDecode(res.body) as Map<String, dynamic>;
        final elements = (j['elements'] as List<dynamic>?) ?? const [];
        for (final el in elements.whereType<Map<String, dynamic>>()) {
          final center = el['center'] as Map<String, dynamic>?;
          final sLat = (el['lat'] as num?)?.toDouble() ??
              (center?['lat'] as num?)?.toDouble();
          final sLng = (el['lon'] as num?)?.toDouble() ??
              (center?['lon'] as num?)?.toDouble();
          if (sLat == null || sLng == null) continue;
          final tags = (el['tags'] as Map<String, dynamic>?) ?? {};
          final name = tags['name']?.toString() ?? 'SPKLU Umum';
          final op = tags['operator']?.toString() ??
              tags['brand']?.toString() ??
              'PLN / OSM';
          final dist = RoutePhysics.haversineKm(
              GeoPointX(lat, lng), GeoPointX(sLat, sLng));
          final id = 'osm_${el['id']}';
          results[id] = SpkluStation(
            id: id,
            name: name,
            operatorName: op,
            lat: sLat,
            lng: sLng,
            socketType: tags['socket:type2'] != null
                ? 'Type 2 / AC'
                : 'SPKLU Universal',
            distanceKm: dist,
          );
        }
      }
    } catch (_) {}

    // 2. OpenChargeMap (optional key supported)
    try {
      final keyPart = ocmApiKey.trim().isNotEmpty ? '&key=${ocmApiKey.trim()}' : '';
      final url = Uri.parse(
        'https://api.openchargemap.io/v3/poi/?output=json&latitude=$lat&longitude=$lng&distance=${(radiusMeters / 1000.0).round()}&distanceunit=KM&maxresults=20$keyPart',
      );
      final res = await _client
          .get(url, headers: _headers)
          .timeout(const Duration(seconds: 9));
      if (res.statusCode == 200) {
        final arr = jsonDecode(res.body) as List<dynamic>;
        for (final item in arr.whereType<Map<String, dynamic>>()) {
          final addr = (item['AddressInfo'] as Map<String, dynamic>?) ?? {};
          final sLat = (addr['Latitude'] as num?)?.toDouble();
          final sLng = (addr['Longitude'] as num?)?.toDouble();
          if (sLat == null || sLng == null) continue;
          final opInfo = item['OperatorInfo'] as Map<String, dynamic>?;
          final conns = (item['Connections'] as List<dynamic>?) ?? const [];
          double? kw;
          String socket = 'AC/DC';
          if (conns.isNotEmpty && conns.first is Map) {
            final c0 = conns.first as Map<String, dynamic>;
            kw = (c0['PowerKW'] as num?)?.toDouble();
            final ct = c0['ConnectionType'] as Map<String, dynamic>?;
            if (ct?['Title'] != null) socket = ct!['Title'].toString();
          }
          final dist = RoutePhysics.haversineKm(
              GeoPointX(lat, lng), GeoPointX(sLat, sLng));
          final id = 'ocm_${item['ID']}';
          results[id] = SpkluStation(
            id: id,
            name: addr['Title']?.toString() ?? 'Stasiun SPKLU',
            operatorName: opInfo?['Title']?.toString() ?? 'OpenChargeMap',
            lat: sLat,
            lng: sLng,
            socketType: socket,
            powerKw: kw,
            distanceKm: dist,
          );
        }
      }
    } catch (_) {}

    final list = results.values.toList()
      ..sort((a, b) => (a.distanceKm ?? 999).compareTo(b.distanceKm ?? 999));
    return list;
  }
}
