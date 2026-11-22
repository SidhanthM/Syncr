import AppKit
import Foundation

/// Remaining-v1 P.1: the QR code, the window's steps, and what the screens show. Nothing here opens a window.
@MainActor func pairingWindowChecks() throws {
    let macId = "0123456789abcdef0123456789abcdef"

    // The link: written as the contract says, read back as it was, whatever the name holds.
    let plain = PairingLink(host: "192.168.1.20", port: 51234, macId: macId, name: "MacBook Air", expires: 1_790_000_120)
    try check(plain.url == "syncr://pair?h=192.168.1.20&p=51234&m=\(macId)&n=MacBook%20Air&e=1790000120", "the link reads h, p, m, n and the expiry (\(plain.url))")
    for name in ["Sid's Mac — pro", "A&B=C+D #1", "日本語のMac", "", String(repeating: "x", count: 64)] {
        let link = PairingLink(host: "10.0.0.2", port: 1, macId: macId, name: name, expires: nil)
        try check(PairingLink.parse(link.url) == link, "a name with \(name.count) characters survives the link")
        try check(!link.url.dropFirst(5).contains(" ") && !link.url.contains("&n=" + name) || name.allSatisfy { $0.isLetter || $0.isNumber } , "the name is escaped")
    }
    for bad in ["https://pair?h=1&p=1&m=\(macId)", "syncr://pair?h=10.0.0.2&p=0&m=\(macId)", "syncr://pair?h=10.0.0.2&p=70000&m=\(macId)", "syncr://pair?h=10.0.0.2&p=5&m=short",
                "syncr://pair?p=5&m=\(macId)", "syncr://pair?h=10.0.0.2&p=5&m=\(macId)&h=10.0.0.3", "syncr://pair"] {
        try check(PairingLink.parse(bad) == nil, "\(bad) is not a pairing link")
    }
    try check(["10.0.0.1", "172.16.5.5", "172.31.0.1", "192.168.0.9", "169.254.1.1"].allSatisfy(PairingLink.isPrivate) &&
              !["8.8.8.8", "172.32.0.1", "192.169.0.1", "127.0.0.1", "nope", "10.0.0"].contains(where: PairingLink.isPrivate), "only private addresses are offered")
    if let address = PairingLink.localAddress() { try check(PairingLink.isPrivate(address), "this Mac's address is on the local network (\(address))") }

    // The code: what a scanner reads from the picture is exactly the link.
    let url = PairingLink(host: "192.168.1.20", port: 51234, macId: macId, name: "Sid's MacBook Air", expires: 1_790_000_120).url
    for pixels in [260, 448, 700] {
        guard let image = QRCode.cgImage(url, pixels: pixels) else { throw CheckFailure(description: "no QR code at \(pixels) px") }
        try check(QRCode.decode(image) == [url], "the QR code at \(pixels) px decodes to the link (\(QRCode.decode(image)))")
    }
    try check(QRCode.image(url, side: 224)?.size == NSSize(width: 224, height: 224), "the image is as large as asked")

    // The window's steps.
    let t0 = Date(timeIntervalSince1970: 1_000_000)
    var model = PairingModel()
    model.offer(nil, reason: "The phone link is off.", now: t0)
    try check(model.stage == .unavailable("The phone link is off."), "no link, no code: the reason shows")
    model.offer(plain, reason: "", now: t0)
    try check(model.stage == .code(link: plain, expires: t0.addingTimeInterval(120)) && model.remaining(now: t0) == 120, "a code is offered for two minutes")
    model.tick(now: t0.addingTimeInterval(119.9))
    try check(model.remaining(now: t0.addingTimeInterval(100)) == 20 && { if case .code = model.stage { true } else { false } }(), "it is still good just before")
    model.tick(now: t0.addingTimeInterval(120))
    try check(model.stage == .expired && model.remaining(now: t0.addingTimeInterval(121)) == nil, "after two minutes it must be asked for again")
    model.offer(plain, reason: "", now: t0.addingTimeInterval(200))
    try check(model.remaining(now: t0.addingTimeInterval(200)) == 120, "a new code starts a new two minutes")
    model.digits(("S23 Ultra", "4719"))
    try check(model.stage == .digits(phone: "S23 Ultra", code: "4719"), "the moment a phone connects the QR code is replaced by four digits and its name")
    model.tick(now: t0.addingTimeInterval(1000))
    try check(model.stage == .digits(phone: "S23 Ultra", code: "4719"), "the digits are not the QR code's to expire")
    model.paired(phone: "S23 Ultra")
    try check(model.stage == .paired(phone: "S23 Ultra"), "typing them pairs")
    model.digits(nil)
    try check(model.stage == .paired(phone: "S23 Ultra"), "the digits going away after pairing changes nothing")
    model.offer(plain, reason: "", now: t0); model.digits(("S23 Ultra", "1111")); model.digits(nil)
    try check(model.stage == .expired, "a phone that gave up, failed or ran out of time sends the window back to asking for a code")
    // The order the Mac really reports it in: the digits are done, then the phone is welcomed as paired.
    model.offer(plain, reason: "", now: t0); model.digits(("S23 Ultra", "1111")); model.digits(nil); model.paired(phone: "S23 Ultra")
    try check(model.stage == .paired(phone: "S23 Ultra"), "digits done, then paired: the window says Paired")
    model.offer(plain, reason: "", now: t0); model.digits(("S23 Ultra", "1111")); model.digits(nil); model.paired(phone: "Another")
    try check(model.stage == .expired, "a different phone's pairing does not turn an ended attempt into Paired")
    model.offer(plain, reason: "", now: t0); model.paired(phone: "Stray")
    try check({ if case .code = model.stage { true } else { false } }(), "a pairing nobody asked this window about is ignored")

    // Every stage draws, in both appearances.
    let view = PairingView()
    let stages: [PairingModel.Stage] = [.unavailable("The phone link is off. Turn it on to pair a phone."), .code(link: plain, expires: t0.addingTimeInterval(120)), .expired,
                                        .digits(phone: "S23 Ultra", code: "4719"), .paired(phone: "S23 Ultra")]
    var sizes: [Int] = []
    for stage in stages {
        view.show(stage, remaining: 95)
        for dark in [false, true] {
            guard let rep = view.snapshot(dark: dark), rep.pixelsWide > 0 else { throw CheckFailure(description: "\(stage) did not draw") }
            sizes.append(rep.pixelsWide)
        }
    }
    try check(sizes.allSatisfy { $0 >= Int(PairingView.size.width) }, "all five steps draw in light and dark")
    // The drawn QR code is what the screen shows.
    view.show(stages[1], remaining: 95)
    if let rep = view.snapshot(dark: true), let cg = rep.cgImage { try check(QRCode.decode(cg).contains(plain.url), "the QR code on the window decodes to the link, in dark mode too") }
}

/// Writes the window in each step, light and dark, as PNG files: `SyncrMacChecks --pairing-shots <folder>`. Evidence, not a gate.
@MainActor func pairingScreenshots(into folder: URL) throws {
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    let link = PairingLink(host: PairingLink.localAddress() ?? "192.168.1.20", port: 51234, macId: "0123456789abcdef0123456789abcdef", name: "MacBook Air", expires: nil)
    let view = PairingView()
    let shots: [(String, PairingModel.Stage, Int?)] = [
        ("mac-1-code", .code(link: link, expires: Date().addingTimeInterval(95)), 95),
        ("mac-2-digits", .digits(phone: "S23 Ultra", code: "4719"), nil),
        ("mac-3-paired", .paired(phone: "S23 Ultra"), nil),
        ("mac-4-expired", .expired, nil),
    ]
    for (name, stage, remaining) in shots {
        view.show(stage, remaining: remaining)
        for dark in [false] {
            guard let rep = view.snapshot(dark: dark), let png = rep.representation(using: .png, properties: [:]) else { continue }
            try png.write(to: folder.appendingPathComponent("\(name)\(dark ? "-dark" : "").png"))
        }
    }
    print("pairing screenshots written to \(folder.path)")
}
