import Foundation

/// What the "Pair a phone" window shows, step by step (remaining-v1 P.1; design page section 5). Pure: the window and the checks
/// both drive it with the time they have.
struct PairingModel: Equatable {
    enum Stage: Equatable {
        /// Nothing to show a code for: the phone link is off or this Mac has no local address.
        case unavailable(String)
        /// The QR code, until `expires`.
        case code(link: PairingLink, expires: Date)
        /// The code ran out, or the phone that came with it did not finish.
        case expired
        /// A phone has connected: its four digits replace the QR code.
        case digits(phone: String, code: String)
        case paired(phone: String)
    }

    private(set) var stage: Stage = .expired
    /// The phone whose digits just went away: the Mac says the digits are done a moment before it says the phone paired.
    private var justEnded: String?

    /// Shows a fresh code for `link` made at `now`, or says why there is none.
    mutating func offer(_ link: PairingLink?, reason: String, now: Date) {
        guard let link else { stage = .unavailable(reason); return }
        justEnded = nil
        stage = .code(link: link, expires: now.addingTimeInterval(PairingLink.lifetime))
    }

    /// The code is only good for two minutes: after that, ask again.
    mutating func tick(now: Date) {
        if case .code(_, let expires) = stage, now >= expires { stage = .expired }
    }

    /// The Mac's four digits for a connected phone appeared, or went away (the phone failed, gave up or ran out of time).
    mutating func digits(_ info: (phone: String, code: String)?) {
        if let info { justEnded = nil; stage = .digits(phone: info.phone, code: info.code); return }
        if case .digits(let phone, _) = stage { justEnded = phone; stage = .expired }
    }

    /// The phone typed the digits. The digits are reported gone first, so this also follows that (for the same phone only).
    mutating func paired(phone: String) {
        if case .digits = stage { stage = .paired(phone: phone); justEnded = nil }
        else if case .expired = stage, justEnded == phone { stage = .paired(phone: phone); justEnded = nil }
    }

    /// Seconds left on the QR code, for the countdown.
    func remaining(now: Date) -> Int? {
        if case .code(_, let expires) = stage { return max(0, Int(expires.timeIntervalSince(now).rounded(.up))) }
        return nil
    }
}
