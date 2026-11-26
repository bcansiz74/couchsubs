// SPDX-License-Identifier: AGPL-3.0-only

using System.Text.Json;
using CouchSubs.Core;

namespace CouchSubs.Core.Tests;

/// <summary>
/// Titreyen harita düzeni (#1365 beta.105, #1391): harita kayıp kararınca düzen düşmez, hazır alanlar okunmaz, liste
/// alanı panelde biter; satırın çizim kipi bir kez seçilir, haritada ikiz yok, boş kutu çizilmez. Ortak deneme dosyası
/// core/Tests/CouchSubsCoreTests/Fixtures/map-flicker.json (Mac MapFlickerTests.swift, Android MapFlickerTest.kt aynı).
/// Sahne çizimi MapLayoutTests ile ortak.
/// </summary>
public class MapFlickerTests
{
    private static JsonDocument Fixture() => JsonDocument.Parse(File.ReadAllText(PackFixtures.Path("map-flicker.json")));

    private static List<string> Strings(JsonElement element, string name) =>
        element.TryGetProperty(name, out var list) ? [.. list.EnumerateArray().Select(item => item.GetString()!)] : [];

    private static MapLayoutTests.Scene Scene(JsonElement phase)
    {
        var p = phase.GetProperty("panel");
        MapLayoutTests.Panel? panel = p.ValueKind == JsonValueKind.Null ? null
            : new MapLayoutTests.Panel(p.GetProperty("side").GetString()!, p.GetProperty("columns").GetInt32(), p.GetProperty("fill").GetInt32());
        return new MapLayoutTests.Scene(
            phase.GetProperty("name").GetString()!, phase.GetProperty("map").GetInt32(), phase.GetProperty("texture").GetBoolean(),
            phase.GetProperty("moving").GetBoolean(), false, panel,
            [.. phase.GetProperty("boxes").EnumerateArray().Select(b => new MapLayoutTests.Box(
                [.. b.GetProperty("cells").EnumerateArray().Select(c => c.GetInt32())], b.GetProperty("fill").GetInt32()))],
            [.. phase.GetProperty("texts").EnumerateArray().Select(t => new MapLayoutTests.Text(
                t.GetProperty("role").GetString()!, t.GetProperty("column").GetInt32(), t.GetProperty("row").GetInt32(),
                t.GetProperty("width").GetInt32(), t.GetProperty("kind").GetString()!))],
            phase.GetProperty("untilMs").GetInt32(), phase.GetProperty("expect").Clone());
    }

    [Fact]
    public void MapLayoutHoldsThroughFlicker()
    {
        using var fixture = Fixture();
        var detector = new ScreenTextDetector();
        var notes = new List<string>();
        detector.OnLog = notes.Add;
        var time = 0;
        foreach (var phase in fixture.RootElement.GetProperty("phases").EnumerateArray())
        {
            var scene = Scene(phase);
            var expect = phase.GetProperty("expect");
            var mapLayout = expect.GetProperty("mapLayout").GetBoolean();
            var everySample = phase.GetProperty("everySample").GetBoolean();
            var end = time + scene.UntilMs;
            while (time <= end)
            {
                detector.Sample(MapLayoutTests.Pixels(scene, time / 100), time, [new CropRegion()]);
                if (everySample) Assert.True(detector.InMapLayout == mapLayout, $"{scene.Name} @{time} ms");
                time += ScreenTextDetector.SampleIntervalMs;
            }
            Assert.True(detector.InMenu == expect.GetProperty("menu").GetBoolean(), $"{scene.Name}: menü");
            Assert.True(detector.InMapLayout == mapLayout, $"{scene.Name}: harita düzeni");
            var areas = detector.Areas;
            foreach (var role in Strings(expect, "together"))
            {
                var held = scene.Texts.Where(t => t.Role == role).Select(t => MapLayoutTests.Holder(areas, t)?.Id).ToList();
                Assert.False(held.Contains(null), $"{scene.Name}: {role} alansız");
                Assert.True(held.Distinct().Count() == 1, $"{scene.Name}: {role} bölündü");
            }
            foreach (var role in Strings(expect, "clipped"))
            {
                var text = scene.Texts.FirstOrDefault(t => t.Role == role);
                var area = text is null ? null : MapLayoutTests.Holder(areas, text);
                Assert.True(scene.Panel is not null && area is not null, $"{scene.Name}: {role} alansız");
                Assert.True(area!.Map, $"{scene.Name}: {role} harita düzeni alanı değil");
                var edge = (double)scene.Panel!.Columns / ScreenTextDetector.Columns;
                if (scene.Panel.Side == "left")
                    Assert.True(area.Region.Right <= edge + 1e-9, $"{scene.Name}: {role} haritaya taşıyor");
                else
                    Assert.True(area.Region.Left >= 1 - edge - 1e-9, $"{scene.Name}: {role} haritaya taşıyor");
            }
            if (expect.TryGetProperty("presetsYield", out var yields) && yields.GetBoolean())
            {
                var kept = RegionWatcher.YieldingPresets([.. GameAreaPresets.Areas("GTA 6"), .. areas]);
                Assert.False(kept.Any(area => area.Id is GameAreaPresets.HelpId or GameAreaPresets.PhoneId), $"{scene.Name}: hazır alan okunuyor");
            }
        }
        var counts = fixture.RootElement.GetProperty("notes");
        var all = string.Join(" | ", notes);
        Assert.True(notes.Count(note => note.StartsWith("ekran menüsü: harita düzeni bulundu", StringComparison.Ordinal))
                    == counts.GetProperty("found").GetInt32(), all);
        Assert.True(notes.Count(note => note == "ekran menüsü: harita düzeni bitti") == counts.GetProperty("ended").GetInt32(), all);
    }

    private static TextBox Box(JsonElement b) =>
        new(b[0].GetDouble(), b[1].GetDouble(), b[2].GetDouble(), b[3].GetDouble());

    /// <summary>Ölçücünün verdiği kipte sentetik ölçü (yanına yazmada sağdaki boş alan, ikizde altta panel).</summary>
    private static TextPaint Paint(string mode, TextBox box)
    {
        var paint = new TextPaint("#202020", box.Bottom - box.Top, box);
        double width = box.Right - box.Left, height = box.Bottom - box.Top;
        switch (mode)
        {
            case "beside":
                var spot = new TextBox(box.Right + 0.01, box.Top, box.Right + 0.01 + width, box.Bottom);
                return paint with { Mode = PaintMode.Beside, Spot = spot, SpotRoom = spot };
            case "twin":
                var twin = new TextBox(box.Left, box.Bottom + 0.01, box.Right, box.Bottom + 0.01 + height);
                return paint with { Mode = PaintMode.Twin, Twin = twin, Spot = twin };
            default:
                return paint with { Mode = PaintMode.Cover };
        }
    }

    [Fact]
    public void RowPaintStaysWhileTextUnchanged()
    {
        using var fixture = Fixture();
        foreach (var sequence in fixture.RootElement.GetProperty("paints").EnumerateArray())
        {
            var name = sequence.GetProperty("name").GetString();
            var map = sequence.GetProperty("map").GetBoolean();
            var expected = sequence.GetProperty("expected").EnumerateObject().ToDictionary(p => p.Name, p => p.Value.GetString());
            var drawn = sequence.GetProperty("drawn").EnumerateArray().Select(read => read.EnumerateArray().Select(t => t.GetString()!).ToList()).ToList();
            var memory = new Dictionary<string, RegionWatcher.RowPaint>();
            var index = 0;
            foreach (var read in sequence.GetProperty("reads").EnumerateArray())
            {
                var rows = read.EnumerateArray().ToList();
                var blocks = rows.Select((row, i) => new RegionWatcher.Block(i, 1, Box(row.GetProperty("box")), 0.04)
                {
                    Paint = Paint(row.GetProperty("mode").GetString()!, Box(row.GetProperty("box"))),
                }).ToList();
                var output = RegionWatcher.SteadyPaints(blocks, [.. rows.Select(row => row.GetProperty("text").GetString()!)], memory, map);
                foreach (var (row, block) in rows.Zip(output))
                {
                    var text = row.GetProperty("text").GetString()!;
                    Assert.True(block.Paint?.Mode == expected[text], $"{name} okuma {index}: {text} ({block.Paint?.Mode})");
                    if (map) Assert.True(block.Paint?.Mode != PaintMode.Twin, $"{name}: haritada ikiz");
                    // Taşınan ölçü satırın yeni yerindedir (örtü satırın üstünde).
                    if (block.Paint is { } paint)
                        Assert.True(Math.Abs(paint.Cover.Top - Box(row.GetProperty("box")).Top) < 1e-9, $"{name}: {text}");
                }
                var items = rows.Select(row => new ScreenTranslation.Item(row.GetProperty("text").GetString()!,
                    row.GetProperty("translation").GetString()!, Box(row.GetProperty("box")), 0.04)).ToList();
                Assert.Equal(drawn[index], RegionWatcher.Drawable(items).Select(item => item.Source));
                index++;
            }
        }
    }

    /// <summary>
    /// Düzenin sürmesi: bulunan düzen panel yerinde durdukça kalır; panel art arda <see cref="ScreenTextDetector.MapMissSamples"/>
    /// örnek yoksa ya da ekran bütünüyle değişirse biter; aynı yanda bir karo oynayan genişlik düzeni değiştirmez.
    /// </summary>
    [Fact]
    public void MapHold()
    {
        var hold = new ScreenTextDetector.MapHold();
        Assert.Null(hold.Next(null, holds: false, sceneChanged: false));
        Assert.Equal(9, hold.Next((true, 9), holds: false, sceneChanged: false)?.Width);
        Assert.Equal(9, hold.Next((true, 10), holds: false, sceneChanged: false)?.Width); // genişlik oynaması
        Assert.Equal(9, hold.Next(null, holds: true, sceneChanged: true)?.Width); // panel yerinde: harita kararsa da sürer
        for (var i = 1; i < ScreenTextDetector.MapMissSamples; i++)
            Assert.NotNull(hold.Next(null, holds: false, sceneChanged: false)); // tek karelik ıska
        Assert.Null(hold.Next(null, holds: false, sceneChanged: false)); // art arda ıska
        Assert.NotNull(hold.Next((false, 8), holds: false, sceneChanged: false));
        Assert.Null(hold.Next(null, holds: false, sceneChanged: true)); // ekran değişti
    }

    /// <summary>Simge ve bozuk okuma süzgeçleri menü dışında da (beta.105: "KM Collect…", "Kh Search…", "ocdich…").</summary>
    [Fact]
    public void FiltersOutsideMenu()
    {
        static IReadOnlyList<string> Read(string text, double? confidence = null)
        {
            var line = new TextLine(text, new TextBox(0.1, 0.3, 0.5, 0.34), confidence);
            return RegionWatcher.MakeCandidate(new Reading([line]), new CropRegion(0.2, 0.5, 0.05, 0.6), auto: true, menu: false).Paragraphs;
        }
        Assert.Equal(["Collect Chemicals for Kyle"], Read("KM Collect Chemicals for Kyle"));
        Assert.Equal(["Search for the Skunk Ape"], Read("Kh Search for the Skunk Ape"));
        Assert.Equal(["Go Find The Boat"], Read("Go Find The Boat"));
        Assert.Empty(Read("ocdich for the onurn Ape", confidence: 0.5));
    }
}
