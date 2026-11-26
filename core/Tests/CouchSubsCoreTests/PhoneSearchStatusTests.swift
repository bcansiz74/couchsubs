// SPDX-License-Identifier: AGPL-3.0-only

import CoreGraphics
import Foundation
import XCTest
@testable import CouchSubsCore

/// GTA 6 telefon aramasının 30 sn'lik durum satırı (X, beta-105: "telefon komple çalışmıyor"; #1385'in beta-104 günlüğünde
/// telefon hiç bulunamayınca tek satır yoktu). Ortak deneme dosyası Fixtures/phone-search-status.json (Windows
/// PhoneSearchStatusTests.cs, Android PhoneSearchStatusTest.kt aynı durumları koşar).
final class PhoneSearchStatusTests: XCTestCase {
    private func vectors() throws -> [String: Any] {
        let url = Bundle.module.url(forResource: "Fixtures", withExtension: nil)!.appendingPathComponent("phone-search-status.json")
        return try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as! [String: Any]
    }

    private static let steps: [String: GamePhoneGeometry.Step] = [
        "noPicture": .noPicture, "noTopSlot": .noTopSlot, "topCrowded": .topCrowded, "noCamera": .noCamera,
        "noBottomSlot": .noBottomSlot, "bottomCrowded": .bottomCrowded, "shape": .shape, "rim": .rim, "sides": .sides,
        "implausible": .implausible, "found": .found,
    ]

    func testSharedCases() throws {
        for item in try vectors()["cases"] as! [[String: Any]] {
            let name = item["name"] as! String
            let status = PhoneSearchStatus()
            let due = item["due"] as! [Bool]
            for (index, sample) in (item["samples"] as! [[String: Any]]).enumerated() {
                var probe: GamePhoneGeometry.Probe?
                if let step = sample["step"] as? String {
                    probe = GamePhoneGeometry.Probe(step: Self.steps[step]!)
                    probe?.level = (sample["level"] as! NSNumber).intValue
                }
                XCTAssertEqual(status.note(probe, seen: sample["seen"] as! Bool, now: (sample["at"] as! NSNumber).intValue),
                               due[index], "\(name) #\(index)")
            }
            let line = status.line(open: item["open"] as! Bool, width: (item["width"] as! NSNumber).intValue,
                                   height: (item["height"] as! NSNumber).intValue, median: (item["median"] as! NSNumber).intValue,
                                   frame: item["frame"] as? String)
            XCTAssertEqual(line, item["line"] as! String, name)
        }
    }

    func testSharedMedians() throws {
        for item in try vectors()["median"] as! [[String: Any]] {
            XCTAssertEqual(PhoneSearchStatus.median((item["tones"] as! [NSNumber]).map(\.intValue)), (item["median"] as! NSNumber).intValue)
        }
    }

    /// Satırdan sonra pencere baştan başlar: sonraki satır yine 30 sn sonra.
    func testWindowRestartsAfterLine() {
        let status = PhoneSearchStatus()
        XCTAssertFalse(status.note(nil, seen: false, now: 0))
        XCTAssertTrue(status.note(nil, seen: false, now: 30_000))
        _ = status.line(open: false, width: 1280, height: 720, median: 0, frame: nil)
        XCTAssertFalse(status.note(nil, seen: false, now: 31_000))
        XCTAssertFalse(status.note(nil, seen: false, now: 60_000))
        XCTAssertTrue(status.note(nil, seen: false, now: 61_000))
    }

    /// İzleyici telefon hiç bulunamasa da 30 sn'de bir satır yazar (önceden sessizdi).
    func testTrackerLogsStatusEvenWhenPhoneIsNeverFound() throws {
        let width = 800, height = 450
        let space = CGColorSpace(name: CGColorSpace.sRGB)!
        let context = try XCTUnwrap(CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4,
                                              space: space, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
        context.setFillColor(CGColor(colorSpace: space, components: [40.0 / 255, 40.0 / 255, 40.0 / 255, 1])!)
        context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        let image = try XCTUnwrap(context.makeImage())
        var lines: [String] = []
        let tracker = GamePhoneTracker()
        tracker.onLog = { lines.append($0) }
        var now = 1_000
        while now <= 32_000 {
            _ = tracker.sample(image, now: now)
            now += 400
        }
        let status = lines.filter { $0.contains("telefon arama durumu") }
        XCTAssertEqual(status.count, 1, "\(lines)")
        let line = try XCTUnwrap(status.first)
        XCTAssertTrue(line.contains("telefon kapalı"), line)
        XCTAssertTrue(line.contains("0 bulundu"), line)
        XCTAssertTrue(line.contains("ortanca parlaklık 40/255"), line)
        XCTAssertTrue(line.contains("kare 800x450, "), line)
    }
}
