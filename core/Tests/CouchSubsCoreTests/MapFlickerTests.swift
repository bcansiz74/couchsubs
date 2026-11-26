// SPDX-License-Identifier: AGPL-3.0-only

import CoreGraphics
import Foundation
import XCTest
@testable import CouchSubsCore

/// Titreyen harita düzeni (#1365 beta.105, #1391): harita kayıp kararınca düzen düşmez, hazır alanlar okunmaz, liste
/// alanı panelde biter; satırın çizim kipi bir kez seçilir, haritada ikiz yok, boş kutu çizilmez. Ortak deneme dosyası
/// Fixtures/map-flicker.json (Windows MapFlickerTests.cs, Android MapFlickerTest.kt aynı).
@MainActor
final class MapFlickerTests: XCTestCase {
    struct Expect: Decodable {
        var menu: Bool
        var mapLayout: Bool
        var together: [String]?
        var clipped: [String]?
        var presetsYield: Bool?
    }

    struct Phase: Decodable {
        var name: String
        var map: Int
        var texture: Bool
        var moving: Bool
        var panel: MapLayoutTests.Panel?
        var boxes: [MapLayoutTests.Box]
        var texts: [MapLayoutTests.Text]
        var untilMs: Int
        var everySample: Bool
        var expect: Expect

        var scene: MapLayoutTests.Scene {
            MapLayoutTests.Scene(name: name, map: map, texture: texture, moving: moving, mirror: nil, panel: panel,
                                 boxes: boxes, texts: texts, untilMs: untilMs,
                                 expect: MapLayoutTests.Expect(menu: expect.menu, mapLayout: expect.mapLayout))
        }
    }

    struct Row: Decodable {
        var text: String
        var box: [Double]
        var mode: String
        var translation: String
    }

    struct Paints: Decodable {
        var name: String
        var map: Bool
        var expected: [String: String]
        var reads: [[Row]]
        var drawn: [[String]]
    }

    struct Notes: Decodable { var found: Int; var ended: Int }

    struct Fixture: Decodable {
        var phases: [Phase]
        var notes: Notes
        var paints: [Paints]
    }

    static func fixture() throws -> Fixture {
        let url = Bundle.module.url(forResource: "Fixtures", withExtension: nil)!.appendingPathComponent("map-flicker.json")
        return try JSONDecoder().decode(Fixture.self, from: Data(contentsOf: url))
    }

    @MainActor
    func testMapLayoutHoldsThroughFlicker() throws {
        let fixture = try Self.fixture()
        let detector = ScreenTextDetector()
        var notes: [String] = []
        detector.onLog = { notes.append($0) }
        var time = 0
        for phase in fixture.phases {
            let scene = phase.scene
            let end = time + phase.untilMs
            while time <= end {
                let image = ScreenTextDetectorTests.image(MapLayoutTests.pixels(scene, phase: time / 100),
                                                          width: MapLayoutTests.width, height: MapLayoutTests.height)
                detector.sample(image, now: time, excluded: [CropRegion()])
                if phase.everySample {
                    XCTAssertEqual(detector.inMapLayout, phase.expect.mapLayout, "\(phase.name) @\(time) ms")
                }
                time += ScreenTextDetector.sampleIntervalMs
            }
            XCTAssertEqual(detector.inMenu, phase.expect.menu, phase.name)
            XCTAssertEqual(detector.inMapLayout, phase.expect.mapLayout, phase.name)
            let areas = detector.areas
            for role in phase.expect.together ?? [] {
                let held = phase.texts.filter { $0.role == role }.map { MapLayoutTests.holder(areas, $0)?.id }
                XCTAssertFalse(held.contains(nil), "\(phase.name): \(role) alansız")
                XCTAssertEqual(Set(held).count, 1, "\(phase.name): \(role) bölündü")
            }
            for role in phase.expect.clipped ?? [] {
                guard let panel = phase.panel,
                      let area = phase.texts.first(where: { $0.role == role }).flatMap({ MapLayoutTests.holder(areas, $0) })
                else { XCTFail("\(phase.name): \(role) alansız"); continue }
                XCTAssertTrue(area.map, "\(phase.name): \(role) harita düzeni alanı değil")
                let edge = Double(panel.columns) / Double(ScreenTextDetector.columns)
                if panel.side == "left" {
                    XCTAssertLessThanOrEqual(area.region.right, edge + 1e-9, "\(phase.name): \(role) haritaya taşıyor")
                } else {
                    XCTAssertGreaterThanOrEqual(area.region.left, 1 - edge - 1e-9, "\(phase.name): \(role) haritaya taşıyor")
                }
            }
            if phase.expect.presetsYield == true {
                let kept = RegionWatcher.yieldingPresets(GameAreaPresets.areas("GTA 6") + areas)
                XCTAssertFalse(kept.contains { GameAreaPresets.isPreset($0.id) }, "\(phase.name): hazır alan okunuyor")
            }
        }
        XCTAssertEqual(notes.filter { $0.hasPrefix("ekran menüsü: harita düzeni bulundu") }.count, fixture.notes.found, "\(notes)")
        XCTAssertEqual(notes.filter { $0 == "ekran menüsü: harita düzeni bitti" }.count, fixture.notes.ended, "\(notes)")
    }

    static func box(_ b: [Double]) -> TextBox { TextBox(left: b[0], top: b[1], right: b[2], bottom: b[3]) }

    /// Ölçücünün verdiği kipte sentetik ölçü (yanına yazmada sağdaki boş alan, ikizde altta panel).
    static func paint(_ mode: String, _ box: TextBox) -> TextPaint {
        var paint = TextPaint(background: "#202020", pitch: box.bottom - box.top, cover: box)
        let width = box.right - box.left, height = box.bottom - box.top
        switch mode {
        case "beside":
            paint.mode = .beside
            paint.spot = TextBox(left: box.right + 0.01, top: box.top, right: box.right + 0.01 + width, bottom: box.bottom)
            paint.spotRoom = paint.spot
        case "twin":
            paint.mode = .twin
            paint.twin = TextBox(left: box.left, top: box.bottom + 0.01, right: box.right, bottom: box.bottom + 0.01 + height)
            paint.spot = paint.twin
        default:
            paint.mode = .cover
        }
        return paint
    }

    func testRowPaintStaysWhileTextUnchanged() throws {
        for sequence in try Self.fixture().paints {
            var memory: [String: RegionWatcher.RowPaint] = [:]
            for (index, read) in sequence.reads.enumerated() {
                let blocks = read.enumerated().map { i, row in
                    RegionWatcher.Block(start: i, count: 1, box: Self.box(row.box), lineHeight: 0.04,
                                        paint: Self.paint(row.mode, Self.box(row.box)))
                }
                let out = RegionWatcher.steadyPaints(blocks, texts: read.map(\.text), memory: &memory, map: sequence.map)
                for (row, block) in zip(read, out) {
                    XCTAssertEqual(block.paint?.mode.rawValue, sequence.expected[row.text],
                                   "\(sequence.name) okuma \(index): \(row.text)")
                    if sequence.map { XCTAssertNotEqual(block.paint?.mode, .twin, "\(sequence.name): haritada ikiz") }
                    // Taşınan ölçü satırın yeni yerindedir (örtü satırın üstünde).
                    if let cover = block.paint?.cover {
                        XCTAssertEqual(cover.top, Self.box(row.box).top, accuracy: 1e-9, "\(sequence.name): \(row.text)")
                    }
                }
                let items = read.map {
                    ScreenTranslation.Item(source: $0.text, translation: $0.translation, box: Self.box($0.box), lineHeight: 0.04)
                }
                XCTAssertEqual(RegionWatcher.drawable(items).map(\.source), sequence.drawn[index], "\(sequence.name) okuma \(index)")
            }
        }
    }

    /// Düzenin sürmesi: bulunan düzen panel yerinde durdukça kalır; panel art arda `mapMissSamples` örnek yoksa ya da
    /// ekran bütünüyle değişirse biter; aynı yanda bir karo oynayan genişlik düzeni değiştirmez.
    func testMapHold() {
        var hold = ScreenTextDetector.MapHold()
        XCTAssertNil(hold.next(found: nil, holds: false, sceneChanged: false))
        XCTAssertEqual(hold.next(found: (true, 9), holds: false, sceneChanged: false)?.width, 9)
        XCTAssertEqual(hold.next(found: (true, 10), holds: false, sceneChanged: false)?.width, 9, "genişlik oynaması")
        XCTAssertEqual(hold.next(found: nil, holds: true, sceneChanged: true)?.width, 9, "panel yerinde: harita kararsa da sürer")
        for _ in 1..<ScreenTextDetector.mapMissSamples {
            XCTAssertNotNil(hold.next(found: nil, holds: false, sceneChanged: false), "tek karelik ıska")
        }
        XCTAssertNil(hold.next(found: nil, holds: false, sceneChanged: false), "art arda ıska")
        XCTAssertNotNil(hold.next(found: (false, 8), holds: false, sceneChanged: false))
        XCTAssertNil(hold.next(found: nil, holds: false, sceneChanged: true), "ekran değişti")
    }

    /// Simge ve bozuk okuma süzgeçleri menü dışında da (beta.105: "KM Collect…", "Kh Search…", "ocdich…").
    func testFiltersOutsideMenu() {
        func read(_ text: String, confidence: Double? = nil) -> [String] {
            let line = TextLine(text, box: TextBox(left: 0.1, top: 0.3, right: 0.5, bottom: 0.34), confidence: confidence)
            return RegionWatcher.candidate(Reading(lines: [line]), in: CropRegion(top: 0.2, bottom: 0.5, left: 0.05, right: 0.6),
                                           auto: true, menu: false).paragraphs
        }
        XCTAssertEqual(read("KM Collect Chemicals for Kyle"), ["Collect Chemicals for Kyle"])
        XCTAssertEqual(read("Kh Search for the Skunk Ape"), ["Search for the Skunk Ape"])
        XCTAssertEqual(read("Go Find The Boat"), ["Go Find The Boat"])
        XCTAssertEqual(read("ocdich for the onurn Ape", confidence: 0.5), [])
    }
}
