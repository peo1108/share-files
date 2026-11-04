import XCTest
@testable import ShareFilesCore

final class PairLinkTests: XCTestCase {
    private let fp = String(repeating: "a", count: 64)

    func testParsesAllFields() throws {
        let link = try XCTUnwrap(PairLink.parse(
            "sharefiles://pair?id=srv1&name=PC%20C%E1%BB%A7a%20Cam&fp=\(fp)&secret=abc-_1&port=48920&addrs=192.168.50.211,10.0.2.2"
        ))
        XCTAssertEqual(link.serverId, "srv1")
        XCTAssertEqual(link.serverName, "PC Của Cam")
        XCTAssertEqual(link.fingerprint, fp)
        XCTAssertEqual(link.secret, "abc-_1")
        XCTAssertEqual(link.port, 48920)
        XCTAssertEqual(link.addresses, ["192.168.50.211", "10.0.2.2"])
    }

    func testToleratesWhitespaceAndEmptyAddresses() throws {
        let link = try XCTUnwrap(PairLink.parse("  sharefiles://pair?id=s&name=n&fp=\(fp)&secret=x&port=1&addrs=  \n"))
        XCTAssertEqual(link.addresses, [])
    }

    func testRejectsInvalidLinks() {
        XCTAssertNil(PairLink.parse("https://evil.example/pair?id=s&fp=\(fp)&secret=x&port=1"))
        XCTAssertNil(PairLink.parse("sharefiles://pair?id=s&name=n&fp=xyz&secret=x&port=1"))
        XCTAssertNil(PairLink.parse("sharefiles://pair?id=s&name=n&fp=\(fp)&port=1"))
        XCTAssertNil(PairLink.parse("sharefiles://pair?id=s&name=n&fp=\(fp)&secret=x&port=99999"))
        XCTAssertNil(PairLink.parse("không phải link"))
    }
}

final class SpeedMeterTests: XCTestCase {
    func testConvergesOnSteadyRate() {
        var meter = SpeedMeter()
        var bytes: Int64 = 0
        var seconds = 0.0
        var speed = 0.0
        for _ in 0..<40 {
            bytes += 50_000_000
            seconds += 0.25
            speed = meter.sample(bytes: bytes, seconds: seconds)
        }
        XCTAssertEqual(speed, 200_000_000, accuracy: 1_000_000)
    }

    func testIgnoresResumeJump() {
        var meter = SpeedMeter()
        _ = meter.sample(bytes: 5_000_000_000, seconds: 0)
        XCTAssertEqual(meter.sample(bytes: 5_000_100_000, seconds: 1), 100_000, accuracy: 1)
    }

    func testNeverNegativeWhenCounterRestarts() {
        var meter = SpeedMeter()
        _ = meter.sample(bytes: 1000, seconds: 0)
        XCTAssertEqual(meter.sample(bytes: 0, seconds: 1), 0)
    }
}

final class TransferPlanTests: XCTestCase {
    func testFreshDownloadHasNoRangeHeaders() {
        XCTAssertEqual(TransferPlan.download(stored: 0, size: 100, etag: "\"v1\""), .fetch(headers: [:]))
    }

    func testResumedDownloadAsksForTheRest() {
        XCTAssertEqual(
            TransferPlan.download(stored: 40, size: 100, etag: "\"v1\""),
            .fetch(headers: ["Range": "bytes=40-", "If-Range": "\"v1\""])
        )
        XCTAssertEqual(TransferPlan.download(stored: 40, size: 100, etag: nil), .fetch(headers: ["Range": "bytes=40-"]))
    }

    func testCompleteDownloadSkipsTheRequest() {
        XCTAssertEqual(TransferPlan.download(stored: 100, size: 100, etag: nil), .alreadyComplete)
    }

    func testEmptyFileStillFetches() {
        XCTAssertEqual(TransferPlan.download(stored: 0, size: 0, etag: nil), .fetch(headers: [:]))
    }

    func testOnlyPartialContentKeepsStoredBytes() {
        XCTAssertTrue(TransferPlan.keepsStoredBytes(status: 206))
        XCTAssertFalse(TransferPlan.keepsStoredBytes(status: 200))
    }

    func testUniqueNameAddsSuffix() {
        XCTAssertEqual(TransferPlan.uniqueName("a.txt") { _ in false }, "a.txt")
        XCTAssertEqual(TransferPlan.uniqueName("a.txt") { $0 == "a.txt" }, "a (1).txt")
        XCTAssertEqual(TransferPlan.uniqueName("a.txt") { ["a.txt", "a (1).txt"].contains($0) }, "a (2).txt")
        XCTAssertEqual(TransferPlan.uniqueName("noext") { $0 == "noext" }, "noext (1)")
    }

    func testQueryEncodesReservedCharacters() {
        XCTAssertEqual(
            TransferPlan.query(["root": "r1", "path": "a+b c&d=f.txt"]),
            "path=a%2Bb%20c%26d%3Df.txt&root=r1"
        )
        XCTAssertEqual(TransferPlan.query(["path": "ảnh"]), "path=%E1%BA%A3nh")
    }

    func testSafeNameStaysInsideItsFolder() {
        XCTAssertEqual(TransferPlan.safeFileName("../../evil.txt"), "evil.txt")
        XCTAssertEqual(TransferPlan.safeFileName("a/b\\c.txt"), "c.txt")
        XCTAssertEqual(TransferPlan.safeFileName(".."), "file")
        XCTAssertEqual(TransferPlan.safeFileName(""), "file")
        XCTAssertEqual(TransferPlan.safeFileName("ảnh đẹp.heic"), "ảnh đẹp.heic")
    }
}

final class ModelsTests: XCTestCase {
    func testDecodesUploadStatusWithNullName() throws {
        let json = #"{"id":"u1","offset":10,"completed":false,"savedName":null}"#
        let status = try JSONDecoder().decode(UploadStatus.self, from: Data(json.utf8))
        XCTAssertEqual(status, UploadStatus(id: "u1", offset: 10, completed: false, savedName: nil))
    }

    func testDecodesBrowseResponse() throws {
        let json = #"{"root":"r","path":"p","entries":[{"name":"a","isDirectory":true,"size":0,"modifiedAt":"2026-11-04T10:00:00Z"}]}"#
        let response = try JSONDecoder().decode(BrowseResponse.self, from: Data(json.utf8))
        XCTAssertEqual(response.entries.first?.isDirectory, true)
    }

    func testDecodesErrorWithOffset() throws {
        let json = #"{"code":"offset_mismatch","message":"x","offset":400}"#
        XCTAssertEqual(try JSONDecoder().decode(ApiErrorBody.self, from: Data(json.utf8)).offset, 400)
    }

    func testFormatsBytes() {
        XCTAssertEqual(ByteFormat.bytes(Int64(999)), "999 B")
        XCTAssertEqual(ByteFormat.bytes(Int64(1_500)), "1,5 KB")
        XCTAssertEqual(ByteFormat.bytes(Int64(250_000_000)), "250 MB")
        XCTAssertEqual(ByteFormat.speed(185_000_000), "185 MB/s")
    }
}
