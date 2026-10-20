import SwiftUI
import WebKit

@main
struct OSINTiOSApp: App {
    var body: some Scene {
        WindowGroup { ContentView() }
    }
}

struct ContentView: View {
    @State private var query = ""
    @State private var domain = ""
    @State private var url = ""
    @State private var email = ""
    @State private var phone = ""
    @State private var selectedEngine = "Google"
    @State private var selectedTool = "Search"
    @State private var browserURL: URL?
    @State private var status = "Ready"
    @State private var showBrowser = false

    private let engines = ["Google", "Bing", "DuckDuckGo", "Brave"]
    private let tools = ["Search", "Domain Lookup", "URL Lookup", "Email Search", "Phone Search", "Social Search", "Google Dorks", "Image Search", "Tor Check", "Official Account Recovery"]

    var body: some View {
        NavigationStack {
            Form {
                Section("OSINT Workspace") {
                    Picker("Tool", selection: $selectedTool) {
                        ForEach(tools, id: \.self) { Text($0) }
                    }
                    Picker("Search engine", selection: $selectedEngine) {
                        ForEach(engines, id: \.self) { Text($0) }
                    }
                    TextField("Search query", text: $query)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    TextField("Domain (example.com)", text: $domain)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    TextField("URL (https://…)", text: $url)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    TextField("Email address", text: $email)
                        .textInputAutocapitalization(.never)
                        .keyboardType(.emailAddress)
                        .autocorrectionDisabled()
                    TextField("Phone number", text: $phone)
                        .keyboardType(.phonePad)
                    Button {
                        runSearch()
                    } label: {
                        Label("Run OSINT Search", systemImage: "magnifyingglass")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    Button {
                        clearFields()
                    } label: {
                        Label("Clear inputs", systemImage: "xmark.circle")
                    }
                }
                Section("Quick access") {
                    quickLink("Public web search", systemImage: "globe", target: URL(string: "https://www.google.com")!)
                    quickLink("Tor Project check", systemImage: "shield.lefthalf.filled", target: URL(string: "https://check.torproject.org/")!)
                    quickLink("HIBP breach check", systemImage: "lock.shield", target: URL(string: "https://haveibeenpwned.com/")!)
                    quickLink("Google Images", systemImage: "photo", target: URL(string: "https://images.google.com/")!)
                    quickLink("Official account recovery", systemImage: "person.badge.key", target: URL(string: "https://support.google.com/accounts/answer/7682439")!)
                }
                Section("Status") {
                    Text(status).font(.footnote).foregroundStyle(.secondary)
                }
                Section {
                    Text("Use OSINT tools only for lawful, authorized research. This iOS edition is an initial native port; Android-only capabilities such as embedded Tor routing, VPN configuration, local file evidence workflows, API integrations, and Android update/permission handling are not yet implemented here.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
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
                                ToolbarItem(placement: .topBarTrailing) {
                                    Button("Done") { showBrowser = false }
                                }
                            }
                    }
                }
            }
        }
    }

    private func quickLink(_ title: String, systemImage: String, target: URL) -> some View {
        Button {
            open(target)
        } label: {
            Label(title, systemImage: systemImage)
        }
    }

    private func runSearch() {
        let raw: String
        switch selectedTool {
        case "Domain Lookup": raw = domain.isEmpty ? query : "site:\(domain) \(query)"
        case "URL Lookup": raw = url.isEmpty ? query : "\(url) \(query)"
        case "Email Search": raw = email.isEmpty ? query : "\"\(email)\""
        case "Phone Search": raw = phone.isEmpty ? query : "\"\(phone)\""
        case "Social Search": raw = query.isEmpty ? (email.isEmpty ? phone : email) : query
        case "Google Dorks": raw = query.isEmpty ? "site:example.com filetype:pdf" : "\(query) (site:github.com OR site:pastebin.com OR filetype:pdf)"
        case "Image Search":
            let term = query.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? query
            open(URL(string: "https://images.google.com/search?q=\(term)")!)
            return
        case "Tor Check":
            open(URL(string: "https://check.torproject.org/")!)
            return
        case "Official Account Recovery":
            open(URL(string: "https://support.google.com/accounts/answer/7682439")!)
            return
        default: raw = query
        }
        guard !raw.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            status = "Enter a query or provide a value for the selected tool."
            return
        }
        let base: String
        switch selectedEngine {
        case "Bing": base = "https://www.bing.com/search?q="
        case "DuckDuckGo": base = "https://duckduckgo.com/?q="
        case "Brave": base = "https://search.brave.com/search?q="
        default: base = "https://www.google.com/search?q="
        }
        let encoded = raw.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? raw
        guard let target = URL(string: base + encoded) else {
            status = "Could not create a valid search URL."
            return
        }
        status = "Searching with \(selectedEngine)…"
        open(target)
    }

    private func open(_ target: URL) {
        browserURL = target
        status = "Opening \(target.host ?? target.absoluteString)"
        showBrowser = true
    }

    private func clearFields() {
        query = ""; domain = ""; url = ""; email = ""; phone = ""
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
