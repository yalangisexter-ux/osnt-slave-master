import SwiftUI
import WebKit
import UIKit
import PhotosUI

@main
struct OSINTiOSApp: App {
    var body: some Scene { WindowGroup { ContentView() } }
}

struct ContentView: View {
    @State private var query = ""
    @State private var domain = ""
    @State private var targetURL = ""
    @State private var email = ""
    @State private var phone = ""
    @State private var name = ""
    @State private var username = ""
    @State private var selectedEngine = "Google"
    @State private var selectedTool = "Public Search"
    @State private var browserURL: URL?
    @State private var status = "Ready"
    @State private var showBrowser = false
    @State private var showSettings = false
    @AppStorage("osint_hibp_api_key") private var hibpApiKey = ""
    @AppStorage("osint_leaksio_api_key") private var leaksIoApiKey = ""
    @State private var reportText = ""
    @State private var showShare = false
    @State private var isCheckingBreach = false
    @State private var selectedPhoto: PhotosPickerItem?
    @State private var evidenceImage: UIImage?

    private let engines = ["Google", "Bing", "DuckDuckGo", "Brave"]
    private let tools = [
        "Public Search", "Domain Search", "URL Investigation", "Email Search", "Phone Search",
        "Username Search", "Social Media Search", "Google Dorks", "Reverse Image Search",
        "Face Search", "Image Search", "Breach Check (HIBP)", "Leaks.io Search",
        "Hotel Data Search", "Social Data Search", "iCloud Exposure Search", "Cloud Storage Search",
        "Telecom Exposure Search", "Dating Site Search", "Nude Content Search", "Live Cameras Search",
        "Escort Site Search", "Dark Web Information", "Tor Connection Check", "Onion Site Directory",
        "VPN Information", "Telegram Bot", "WhatsApp Bot", "Video Search", "Live Streams",
        "Browse Files on Web", "Browse Photos on Web", "Browse Documents", "Browse PDFs",
        "Password Hash Lookup", "Wi-Fi Security Audit Guidance", "Official Account Recovery",
        "Export Investigation Notes", "API Key Manager", "Settings", "About"
    ]

    var body: some View {
        NavigationStack {
            List {
                Section("Search workspace") {
                    Picker("Tool", selection: $selectedTool) {
                        ForEach(tools, id: \.self) { Text($0) }
                    }
                    Picker("Search engine", selection: $selectedEngine) {
                        ForEach(engines, id: \.self) { Text($0) }
                    }
                    input("Query / keywords", value: $query)
                    input("Domain", value: $domain)
                    input("URL", value: $targetURL)
                    input("Email", value: $email, capitalization: .never, keyboard: .emailAddress)
                    input("Phone", value: $phone, keyboard: .phonePad)
                    input("Name", value: $name)
                    input("Username / social handle", value: $username, capitalization: .never)
                    Button { runTool() } label: {
                        Label("Run selected tool", systemImage: "magnifyingglass")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    Button { clearFields() } label: {
                        Label("Clear inputs", systemImage: "xmark.circle")
                    }
                }

                Section("Quick access") {
                    quickLink("Public web search", "globe", "https://www.google.com")
                    quickLink("Google Images", "photo", "https://images.google.com/")
                    quickLink("Have I Been Pwned", "lock.shield", "https://haveibeenpwned.com/")
                    quickLink("Tor Project check", "shield.lefthalf.filled", "https://check.torproject.org/")
                    quickLink("Onion search information", "network", "https://ahmia.fi/")
                    quickLink("Google advanced search", "text.magnifyingglass", "https://www.google.com/advanced_search")
                    quickLink("Official account recovery", "person.badge.key", "https://support.google.com/accounts/answer/7682439")
                }

                Section("Evidence and configuration") {
                    PhotosPicker(selection: $selectedPhoto, matching: .images) {
                        Label("Attach evidence image", systemImage: "photo.badge.plus")
                    }
                    if let evidenceImage {
                        Image(uiImage: evidenceImage).resizable().scaledToFit().frame(maxHeight: 180)
                        Button(role: .destructive) { self.evidenceImage = nil; selectedPhoto = nil } label: {
                            Label("Remove evidence image", systemImage: "trash")
                        }
                    }
                    Button { prepareReport() } label: {
                        Label("Create / export investigation notes", systemImage: "doc.text")
                    }
                    Button { showSettings = true } label: {
                        Label("API keys and settings", systemImage: "gearshape")
                    }
                    .sheet(isPresented: $showSettings) {
                        NavigationStack {
                            Form {
                                Section("API integrations") {
                                    SecureField("Have I Been Pwned API key", text: keyBinding("hibp"))
                                    SecureField("Leaks.io API key", text: keyBinding("leaksio"))
                                    Text("Keys are stored only in this app's local preferences. No API key is bundled in the app.")
                                        .font(.footnote).foregroundStyle(.secondary)
                                }
                                Section("Platform compatibility") {
                                    Text("This iOS port uses native web search and WKWebView. Android-specific Tor binary execution, VPN service integration, background processes, Play Protect controls, Android file-system browsing and Android updater functions require separate iOS-compatible implementations and permissions.")
                                        .font(.footnote).foregroundStyle(.secondary)
                                }
                            }
                            .navigationTitle("Settings")
                            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { showSettings = false } } }
                        }
                    }
                    Button { status = "OSINT Workspace iOS • Initial feature port • iOS 16+" } label: {
                        Label("About", systemImage: "info.circle")
                    }
                }

                Section("Status") {
                    Text(status).font(.footnote).foregroundStyle(.secondary)
                }

                Section("Responsible use") {
                    Text("Use these tools only for lawful, authorized research. Breach checks should use accounts you own or have permission to investigate. OTP bypass and credential brute-force attacks are not provided; account recovery links and Wi-Fi security audit guidance are used instead.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
            }
            .navigationTitle("OSINT Workspace")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { clearFields() } label: { Image(systemName: "arrow.counterclockwise") }
                        .accessibilityLabel("Reset inputs")
                }
            }
            .sheet(isPresented: $showBrowser) {
                if let target = browserURL {
                    NavigationStack {
                        BrowserView(url: target)
                            .navigationTitle("OSINT Browser")
                            .navigationBarTitleDisplayMode(.inline)
                            .toolbar {
                                ToolbarItem(placement: .topBarLeading) {
                                    Button { browserURL = browserURL.flatMap { URL(string: $0.absoluteString) } } label: { Image(systemName: "arrow.clockwise") }
                                }
                                ToolbarItem(placement: .topBarTrailing) { Button("Done") { showBrowser = false } }
                            }
                    }
                }
            }
            .sheet(isPresented: $showShare) {
                ShareSheet(items: evidenceImage.map { [reportText as Any, $0 as Any] } ?? [reportText as Any])
            }
            .onChange(of: selectedPhoto) { newItem in
                guard let newItem else { return }
                Task {
                    if let data = try? await newItem.loadTransferable(type: Data.self), let image = UIImage(data: data) {
                        evidenceImage = image
                        status = "Evidence image attached; export notes to share it."
                    } else {
                        status = "Could not load the selected image."
                    }
                }
            }
        }
    }

    private enum Keyboard { case normal, emailAddress, phonePad }
    private func input(_ title: String, value: Binding<String>, capitalization: TextInputAutocapitalization = .sentences, keyboard: Keyboard = .normal) -> some View {
        Group {
            switch keyboard {
            case .emailAddress:
                TextField(title, text: value).textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.emailAddress)
            case .phonePad:
                TextField(title, text: value).keyboardType(.phonePad)
            case .normal:
                TextField(title, text: value).textInputAutocapitalization(capitalization).autocorrectionDisabled()
            }
        }
    }

    private func quickLink(_ title: String, _ icon: String, _ url: String) -> some View {
        Button { if let target = URL(string: url) { open(target) } } label: { Label(title, systemImage: icon) }
    }

    private func encoded(_ value: String) -> String {
        value.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? value
    }

    private func searchURL(_ term: String, engine: String? = nil) -> URL? {
        let base: String
        switch engine ?? selectedEngine {
        case "Bing": base = "https://www.bing.com/search?q="
        case "DuckDuckGo": base = "https://duckduckgo.com/?q="
        case "Brave": base = "https://search.brave.com/search?q="
        default: base = "https://www.google.com/search?q="
        }
        return URL(string: base + encoded(term))
    }

    private func runTool() {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        let d = domain.trimmingCharacters(in: .whitespacesAndNewlines)
        let u = targetURL.trimmingCharacters(in: .whitespacesAndNewlines)
        let e = email.trimmingCharacters(in: .whitespacesAndNewlines)
        let p = phone.trimmingCharacters(in: .whitespacesAndNewlines)
        let n = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let handle = username.trimmingCharacters(in: .whitespacesAndNewlines)
        var term = q
        var direct: String?

        switch selectedTool {
        case "Domain Search":
            let host = d.isEmpty ? q : d
            guard !host.isEmpty else { status = "Enter a domain."; return }
            Task { await inspectDomain(host) }
            return
        case "URL Investigation":
            let raw = u.isEmpty ? q : u
            guard !raw.isEmpty else { status = "Enter a URL."; return }
            Task { await inspectURL(raw) }
            return
        case "Email Search": term = "\"\(e.isEmpty ? q : e)\""
        case "Phone Search": term = "\"\(p.isEmpty ? q : p)\""
        case "Username Search": term = "\"\(handle.isEmpty ? q : handle)\" (profile OR username)"
        case "Social Media Search": term = "\"\(handle.isEmpty ? (e.isEmpty ? q : e) : handle)\" (site:facebook.com OR site:instagram.com OR site:linkedin.com OR site:x.com OR site:tiktok.com)"
        case "Google Dorks": term = q.isEmpty ? "site:\(d.isEmpty ? "example.com" : d) filetype:pdf" : "\(q) (site:github.com OR site:pastebin.com OR filetype:pdf)"
        case "Reverse Image Search": direct = "https://lens.google.com/"
        case "Face Search": direct = "https://images.google.com/"
        case "Image Search": direct = "https://images.google.com/search?q=\(encoded(q.isEmpty ? n : q))"
        case "Breach Check (HIBP)":
            let account = e.isEmpty ? q : e
            guard account.contains("@"), !hibpApiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                status = "Enter an email address and add your Have I Been Pwned API key in Settings."
                showSettings = hibpApiKey.isEmpty
                return
            }
            Task { await checkHIBP(account: account) }
            return
        case "Leaks.io Search":
            direct = "https://leaks.io/"
            status = "Opened Leaks.io. API-backed checks require a valid API key and supported endpoint."
        case "Hotel Data Search": term = "\"\(q.isEmpty ? e : q)\" (hotel OR booking OR reservation) data"
        case "Social Data Search": term = "\"\(handle.isEmpty ? q : handle)\" (social profile OR social account)"
        case "iCloud Exposure Search": term = "\"\(e.isEmpty ? q : e)\" (iCloud OR Apple ID) exposure"
        case "Cloud Storage Search": term = "\"\(q)\" (site:drive.google.com OR site:dropbox.com OR site:onedrive.live.com)"
        case "Telecom Exposure Search": term = "\"\(p.isEmpty ? q : p)\" (telecom OR phone records)"
        case "Dating Site Search": term = "\"\(handle.isEmpty ? q : handle)\" (dating OR profile)"
        case "Nude Content Search": term = q.isEmpty ? "\"\(n)\" image" : q
        case "Live Cameras Search": term = "publicly accessible camera stream \(q)"
        case "Escort Site Search": term = "\"\(q.isEmpty ? n : q)\" public web"
        case "Dark Web Information": direct = "https://ahmia.fi/"
        case "Tor Connection Check": direct = "https://check.torproject.org/"
        case "Onion Site Directory": direct = "https://ahmia.fi/"
        case "VPN Information": direct = "https://support.apple.com/guide/iphone/configure-a-vpn-iph6c4f3b6dc/ios"
        case "Telegram Bot": direct = "https://t.me/"
        case "WhatsApp Bot": direct = "https://www.whatsapp.com/"
        case "Video Search": direct = "https://www.youtube.com/results?search_query=\(encoded(q))"
        case "Live Streams": direct = "https://www.youtube.com/results?search_query=\(encoded(q.isEmpty ? "live stream" : q + " live"))"
        case "Browse Files on Web": term = "\(q) (filetype:txt OR filetype:csv OR filetype:xlsx)"
        case "Browse Photos on Web": direct = "https://images.google.com/search?q=\(encoded(q))"
        case "Browse Documents": term = "\(q) (filetype:doc OR filetype:docx OR filetype:odt)"
        case "Browse PDFs": term = "\(q) filetype:pdf"
        case "Password Hash Lookup": direct = "https://www.google.com/search?q=\(encoded(q + " hash lookup"))"
        case "Wi-Fi Security Audit Guidance": direct = "https://support.apple.com/guide/security/welcome/web"
            status = "For Wi-Fi security, review your own router/admin console, use WPA2-AES or WPA3, disable WPS, and update firmware. This app does not brute-force passwords."
        case "Official Account Recovery": direct = "https://support.google.com/accounts/answer/7682439"
        case "Export Investigation Notes": prepareReport(); return
        case "API Key Manager", "Settings": showSettings = true; return
        case "About": status = "OSINT Workspace iOS • Initial native port"; return
        default: break
        }

        if let direct, let target = URL(string: direct) {
            open(target)
            return
        }
        if term.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            status = "Enter a query or the value required by the selected tool."
            return
        }
        guard let target = searchURL(term) else {
            status = "Could not create a valid search URL."
            return
        }
        status = "Searching for: \(term)"
        open(target)
    }

    @MainActor
    private func inspectDomain(_ rawDomain: String) async {
        let host = rawDomain.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "https://", with: "")
            .replacingOccurrences(of: "http://", with: "")
            .split(separator: "/").first.map(String.init) ?? ""
        guard !host.isEmpty, !host.contains(" ") else { status = "Enter a valid domain."; return }
        status = "Looking up public DNS records for " + host + "…"
        var records: [String] = []
        for type in ["A", "AAAA", "MX", "NS", "TXT"] {
            guard var parts = URLComponents(string: "https://cloudflare-dns.com/dns-query") else { continue }
            parts.queryItems = [URLQueryItem(name: "name", value: host), URLQueryItem(name: "type", value: type)]
            guard let url = parts.url else { continue }
            var request = URLRequest(url: url)
            request.setValue("application/dns-json", forHTTPHeaderField: "accept")
            do {
                let (data, _) = try await URLSession.shared.data(for: request)
                if let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                   let answers = json["Answer"] as? [[String: Any]] {
                    let values = answers.compactMap { $0["data"] as? String }
                    if !values.isEmpty { records.append(type + ": " + values.joined(separator: ", ")) }
                }
            } catch { records.append(type + ": lookup failed") }
        }
        let result = records.isEmpty ? "No DNS answers returned or DNS service unavailable." : records.joined(separator: "\n")
        status = "Domain: " + host + "\n" + result
        reportText = "OSINT Workspace — Domain/DNS Lookup\nDate: " + ISO8601DateFormatter().string(from: Date()) + "\nDomain: " + host + "\n" + result
    }

    @MainActor
    private func inspectURL(_ rawURL: String) async {
        let normalized = rawURL.contains("://") ? rawURL : "https://" + rawURL
        guard let target = URL(string: normalized), let scheme = target.scheme?.lowercased(),
              ["https", "http"].contains(scheme), let host = target.host else { status = "Enter a valid HTTP or HTTPS URL."; return }
        status = "Checking URL response and security headers…"
        var request = URLRequest(url: target, timeoutInterval: 15)
        request.httpMethod = "HEAD"
        do {
            var (_, response) = try await URLSession.shared.data(for: request)
            var http = response as? HTTPURLResponse
            if http?.statusCode == 405 {
                request.httpMethod = "GET"
                (_, response) = try await URLSession.shared.data(for: request)
                http = response as? HTTPURLResponse
            }
            guard let http else { status = "The server returned a non-HTTP response."; return }
            let names = ["Strict-Transport-Security", "Content-Security-Policy", "X-Content-Type-Options", "X-Frame-Options", "Referrer-Policy"]
            let headers = names.map { $0 + ": " + (http.value(forHTTPHeaderField: $0) ?? "Not present") }.joined(separator: "\n")
            let result = "URL: " + (http.url?.absoluteString ?? target.absoluteString) + "\nHost: " + host + "\nScheme: " + scheme + "\nHTTP status: " + String(http.statusCode) + "\n\nSecurity headers:\n" + headers
            status = result
            reportText = "OSINT Workspace — URL Inspection\nDate: " + ISO8601DateFormatter().string(from: Date()) + "\n" + result
        } catch { status = "URL request failed: " + error.localizedDescription }
    }
    @MainActor
    private func checkHIBP(account: String) async {
        guard let encodedAccount = account.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed),
              let url = URL(string: "https://haveibeenpwned.com/api/v3/breachedaccount/\(encodedAccount)?truncateResponse=true") else {
            status = "Could not prepare the breach-check request."
            return
        }
        isCheckingBreach = true
        status = "Checking Have I Been Pwned for the supplied account…"
        defer { isCheckingBreach = false }
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue(hibpApiKey, forHTTPHeaderField: "hibp-api-key")
        request.setValue("OSINT-Workspace-iOS", forHTTPHeaderField: "user-agent")
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let http = response as? HTTPURLResponse else {
                status = "HIBP returned an invalid response."
                return
            }
            switch http.statusCode {
            case 200:
                let decoded = try JSONSerialization.jsonObject(with: data)
                let count = (decoded as? [[String: Any]])?.count ?? 0
                status = "HIBP returned \(count) breach record(s) for the supplied account."
                reportText = "OSINT Workspace — HIBP Check\nDate: \(ISO8601DateFormatter().string(from: Date()))\nAccount: \(account)\nResult: \(count) breach record(s) returned.\nUse only for accounts you own or are authorized to check."
            case 404:
                status = "HIBP reports no known breach records for the supplied account."
                reportText = "OSINT Workspace — HIBP Check\nDate: \(ISO8601DateFormatter().string(from: Date()))\nAccount: \(account)\nResult: No known breach records returned. This does not guarantee the account has never been exposed."
            case 401, 403:
                status = "HIBP rejected the API key or access. Verify your key/subscription in Settings."
            case 429:
                status = "HIBP rate limit reached. Wait before trying again."
            default:
                status = "HIBP request failed with HTTP \(http.statusCode)."
            }
        } catch {
            status = "HIBP request failed: \(error.localizedDescription)"
        }
    }

    private func open(_ target: URL) {
        browserURL = target
        status = "Opening \(target.host ?? target.absoluteString)"
        showBrowser = true
    }

    private func keyBinding(_ name: String) -> Binding<String> {
        if name == "hibp" {
            return Binding(get: { hibpApiKey }, set: { hibpApiKey = $0 })
        }
        return Binding(get: { leaksIoApiKey }, set: { leaksIoApiKey = $0 })
    }

    private func prepareReport() {
        reportText = """
        OSINT Workspace — Investigation Notes
        Date: \(ISO8601DateFormatter().string(from: Date()))
        Tool: \(selectedTool)
        Search engine: \(selectedEngine)
        Query: \(query)
        Domain: \(domain)
        URL: \(targetURL)
        Email: \(email)
        Phone: \(phone)
        Name: \(name)
        Username: \(username)
        Status: \(status)

        Note: Record only information obtained lawfully and with authorization.
        """
        showShare = true
    }

    private func clearFields() {
        query = ""; domain = ""; targetURL = ""; email = ""; phone = ""; name = ""; username = ""
        status = "Inputs cleared."
    }
}

struct BrowserView: UIViewRepresentable {
    let url: URL
    func makeUIView(context: Context) -> WKWebView {
        let view = WKWebView(frame: .zero)
        view.allowsBackForwardNavigationGestures = true
        view.load(URLRequest(url: url))
        return view
    }
    func updateUIView(_ uiView: WKWebView, context: Context) {
        if uiView.url != url { uiView.load(URLRequest(url: url)) }
    }
}

struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
