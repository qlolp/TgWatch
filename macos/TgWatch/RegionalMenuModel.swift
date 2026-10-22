import Foundation
import CoreFoundation

enum RegionalMenuService: String, CaseIterable {
    case youtube, gemini, notebook, antigravity, dots
    var title: String {
        switch self {
        case .youtube: return "YouTube"
        case .gemini: return "Gemini"
        case .notebook: return "Notebook"
        case .antigravity: return "Antigravity"
        case .dots: return "ChatGPT / dots"
        }
    }
    var address: URL {
        let address: String
        switch self {
        case .youtube: address = "https://www.youtube.com/"
        case .gemini: address = "https://gemini.google.com/"
        case .notebook: address = "https://notebook.google.com/"
        case .antigravity: address = "https://antigravity.google/"
        case .dots: address = "https://chatgpt.com/dots/home"
        }
        return URL(string: address)!
    }
}

enum RegionalMenuStatus: String {
    case webReachable = "WEB_REACHABLE", regionRestricted = "REGION_RESTRICTED"
    case authRequired = "AUTH_REQUIRED", challenge = "CHALLENGE", rateLimited = "RATE_LIMITED"
    case networkError = "NETWORK_ERROR", unknown = "UNKNOWN"
    var title: String {
        switch self {
        case .webReachable: return "Public website responds"
        case .regionRestricted: return "Explicit regional refusal"
        case .authRequired: return "Sign-in required"
        case .challenge: return "CAPTCHA / browser verification"
        case .rateLimited: return "Requests limited"
        case .networkError: return "Network error"
        case .unknown: return "Unverified"
        }
    }
}

struct RegionalMenuReading {
    let service: RegionalMenuService
    let status: RegionalMenuStatus
    let httpCode: Int?
    let country: String?
    let checkedAt: Double
    let regionConfirmed: Bool
    let contextID: String?
    let receivedUptime: TimeInterval
}

/// Private flat ASCII IPC: no URLs, arbitrary text, route/egress fingerprints or account data.
/// The public CLI JSON schema is intentionally not accepted as this app-only protocol.
enum RegionalMenuDecoder {
    static let maximumBytes = 4_096
    static let maximumAgeMS: Double = 600_000
    private static let keys: Set<String> = ["schema", "source", "service", "status", "http_code", "country",
        "checked_at", "region_confirmed", "context_id"]
    private static let keyPattern = try! NSRegularExpression(pattern: #""[a-z_]+"\s*:"#)
    private static let countries = Set((
        "AD AE AF AG AI AL AM AO AQ AR AS AT AU AW AX AZ BA BB BD BE BF BG BH BI BJ BL BM BN BO BQ BR BS " +
        "BT BV BW BY BZ CA CC CD CF CG CH CI CK CL CM CN CO CR CU CV CW CX CY CZ DE DJ DK DM DO DZ EC EE " +
        "EG EH ER ES ET FI FJ FK FM FO FR GA GB GD GE GF GG GH GI GL GM GN GP GQ GR GS GT GU GW GY HK HM " +
        "HN HR HT HU ID IE IL IM IN IO IQ IR IS IT JE JM JO JP KE KG KH KI KM KN KP KR KW KY KZ LA LB LC " +
        "LI LK LR LS LT LU LV LY MA MC MD ME MF MG MH MK ML MM MN MO MP MQ MR MS MT MU MV MW MX MY MZ NA " +
        "NC NE NF NG NI NL NO NP NR NU NZ OM PA PE PF PG PH PK PL PM PN PR PS PT PW PY QA RE RO RS RU RW " +
        "SA SB SC SD SE SG SH SI SJ SK SL SM SN SO SR SS ST SV SX SY SZ TC TD TF TG TH TJ TK TL TM TN TO " +
        "TR TT TV TW TZ UA UG UM US UY UZ VA VC VE VG VI VN VU WF WS YE YT ZA ZM ZW"
    ).split(separator: " ").map(String.init))

    static func decode(_ data: Data, now: Double, uptime: TimeInterval) -> RegionalMenuReading? {
        guard data.count <= maximumBytes, data.allSatisfy({ $0 < 128 }),
              let text = String(data: data, encoding: .utf8), !text.contains("\\"),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              Set(object.keys) == keys,
              keyPattern.numberOfMatches(in: text, range: NSRange(text.startIndex..., in: text)) == keys.count,
              integer(object["schema"]) == 1,
              let source = object["source"] as? String, ["mac_public", "computer_public"].contains(source),
              let rawService = object["service"] as? String, let service = RegionalMenuService(rawValue: rawService),
              let rawStatus = object["status"] as? String, let status = RegionalMenuStatus(rawValue: rawStatus),
              let at = integer(object["checked_at"]), at >= 0, now.isFinite, at <= now, now - at <= maximumAgeMS,
              uptime.isFinite, uptime >= 0, let confirmed = boolean(object["region_confirmed"]),
              let code = httpCode(object["http_code"]),
              let country = optionalString(object["country"]),
              let context = optionalString(object["context_id"]) else { return nil }
        guard country == nil || countries.contains(country!),
              context == nil || context!.range(of: "^[0-9a-f]{32}$", options: .regularExpression) != nil,
              !confirmed || (status == .regionRestricted && context != nil),
              context != nil || (status == .unknown && country == nil && !confirmed) else { return nil }
        return RegionalMenuReading(service: service, status: status, httpCode: code, country: country,
            checkedAt: at, regionConfirmed: confirmed, contextID: context, receivedUptime: uptime)
    }

    private static func integer(_ value: Any?) -> Double? {
        guard let number = value as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID(),
              number.doubleValue.isFinite, number.doubleValue.rounded(.towardZero) == number.doubleValue
        else { return nil }
        return number.doubleValue
    }
    private static func boolean(_ value: Any?) -> Bool? {
        guard let number = value as? NSNumber, CFGetTypeID(number) == CFBooleanGetTypeID() else { return nil }
        return number.boolValue
    }
    // Double optional distinguishes a JSON null from an invalid value.
    private static func optionalString(_ value: Any?) -> String?? {
        if value is NSNull { return .some(nil) }
        guard let string = value as? String else { return nil }
        return .some(string)
    }
    private static func httpCode(_ value: Any?) -> Int?? {
        if value is NSNull { return .some(nil) }
        guard let number = integer(value), (100...599).contains(number) else { return nil }
        return .some(Int(number))
    }
}

struct RegionalMenuModel {
    private(set) var generation: UInt64 = 0
    private(set) var services: Set<RegionalMenuService> = []
    private(set) var contextID: String?
    private var readings: [RegionalMenuService: RegionalMenuReading] = [:]

    mutating func replace(generation: UInt64, services: Set<RegionalMenuService>) {
        self.generation = generation
        self.services = services
        contextID = nil
        readings.removeAll()
    }
    @discardableResult mutating func ingest(_ data: Data, generation: UInt64, now: Double,
                                            uptime: TimeInterval) -> Bool {
        guard generation == self.generation,
              let reading = RegionalMenuDecoder.decode(data, now: now, uptime: uptime),
              services.contains(reading.service) else { return false }
        if let previous = readings[reading.service], reading.checkedAt < previous.checkedAt { return false }
        if reading.contextID != contextID || reading.contextID == nil { readings.removeAll() }
        contextID = reading.contextID
        readings[reading.service] = reading
        return true
    }
    func current(_ service: RegionalMenuService, now: Double, uptime: TimeInterval) -> RegionalMenuReading? {
        guard let reading = readings[service], reading.contextID != nil, reading.contextID == contextID,
              now >= reading.checkedAt, now - reading.checkedAt <= RegionalMenuDecoder.maximumAgeMS,
              uptime >= reading.receivedUptime, uptime - reading.receivedUptime <= RegionalMenuDecoder.maximumAgeMS / 1_000
        else { return nil }
        return reading
    }
}
