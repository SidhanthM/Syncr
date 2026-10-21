import AppKit
import SyncrCore
import SyncrCompanion

@MainActor final class CompanionSettingsWindow:NSObject {
    private unowned let controller:CompanionController
    private let window:NSWindow
    private let provider = NSPopUpButton(), reading = NSPopUpButton(), asking = NSPopUpButton()
    private let state = NSTextField(wrappingLabelWithString:"Checking sign-in…"), key = NSSecureTextField(string:"")
    private let tokens = NSTextField(string:"100000"), requests = NSTextField(string:"100")
    private let drawings = NSButton(checkboxWithTitle:"Experimental drawings (off until the owner accepts the sketch trial)",target:nil,action:nil)
    private let readers = NSTextView()
    init(controller:CompanionController) {
        self.controller = controller
        window = NSWindow(contentRect:NSRect(x:0,y:0,width:680,height:690),styleMask:[.titled,.closable,.resizable],backing:.buffered,defer:false)
        super.init(); window.title = "Syncr Companion Settings"; window.isReleasedWhenClosed = false
        provider.addItems(withTitles:["Codex subscription (pi, Codex CLI for Web)","Claude API key"])
        provider.selectItem(at:controller.preferences.provider == "claude" ? 1 : 0)
        provider.target = self; provider.action = #selector(providerChanged)
        key.placeholderString = "Claude API key — saved only in Keychain"
        drawings.state = controller.preferences.drawings ? .on : .off; tokens.stringValue = String(controller.preferences.tokenLimit); requests.stringValue = String(controller.preferences.requestLimit)
        let sign = ActionButton("Sign in with Codex…") { [weak self] in self?.signIn() }
        let refresh = ActionButton("Refresh models") { [weak controller] in controller?.refreshModels() }
        let set = ActionButton("Set key") { [weak self] in do { guard let self else{return}; try ClaudeKeychain.set(self.key.stringValue); self.key.stringValue = ""; self.controller.refreshModels() } catch { self?.state.stringValue = error.localizedDescription } }
        let delete = ActionButton("Delete key") { [weak self] in do { try ClaudeKeychain.delete(); self?.controller.refreshModels() } catch { self?.state.stringValue = error.localizedDescription } }
        let scroll = NSScrollView(); scroll.hasVerticalScroller = true; scroll.documentView = readers; readers.isRichText = false; readers.font = .monospacedSystemFont(ofSize:11,weight:.regular); readers.frame = NSRect(x:0,y:0,width:620,height:600); readers.autoresizingMask = [.width]
        let encoder = JSONEncoder(); encoder.outputFormatting = [.prettyPrinted,.sortedKeys]
        readers.string = String(decoding:(try? encoder.encode(controller.readers)) ?? Data(),as:UTF8.self)
        let saveReaders = ActionButton("Save readers") { [weak self] in
            do { guard let self else{return}; let values = try JSONDecoder().decode([Reader].self,from:Data(self.readers.string.utf8)); try self.controller.saveReaders(values); self.state.stringValue = "Readers saved" } catch { self?.state.stringValue = error.localizedDescription }
        }
        let save = ActionButton("Save settings") { [weak self] in self?.save() }
        let stack = NSStackView(views:[NSTextField(labelWithString:"Provider and sign-in"),provider,state,NSStackView(views:[sign,refresh]),NSStackView(views:[key,set,delete]),NSTextField(labelWithString:"Reading-along model"),reading,NSTextField(labelWithString:"Asking and drawing model"),asking,NSStackView(views:[NSTextField(labelWithString:"Daily tokens"),tokens,NSTextField(labelWithString:"Unmetered requests"),requests]),drawings,NSTextField(labelWithString:"Readers — JSON array (id, name, description)"),scroll,NSStackView(views:[saveReaders,save])])
        stack.orientation = .vertical; stack.alignment = .leading; stack.spacing = 10; stack.edgeInsets = NSEdgeInsets(top:18,left:18,bottom:18,right:18); stack.translatesAutoresizingMaskIntoConstraints = false
        let content = NSView(); content.addSubview(stack); window.contentView = content
        NSLayoutConstraint.activate([stack.leadingAnchor.constraint(equalTo:content.leadingAnchor),stack.trailingAnchor.constraint(equalTo:content.trailingAnchor),stack.topAnchor.constraint(equalTo:content.topAnchor),stack.bottomAnchor.constraint(equalTo:content.bottomAnchor),scroll.heightAnchor.constraint(greaterThanOrEqualToConstant:150),scroll.widthAnchor.constraint(equalTo:stack.widthAnchor),key.widthAnchor.constraint(greaterThanOrEqualToConstant:320),reading.widthAnchor.constraint(equalTo:stack.widthAnchor),asking.widthAnchor.constraint(equalTo:stack.widthAnchor)])
    }
    func show() { window.center(); window.makeKeyAndOrderFront(nil) }
    func close() { window.close() }
    func update(models:[ProviderModel],state:String) {
        self.state.stringValue = state
        for (popup,selected) in [(reading,controller.preferences.readingModel),(asking,controller.preferences.askingModel)] {
            popup.removeAllItems(); popup.addItems(withTitles:models.map(\.id))
            if popup.itemTitles.contains(selected) { popup.selectItem(withTitle:selected) }
            else if !selected.isEmpty { popup.addItem(withTitle:selected + " (not offered)"); popup.selectItem(at:popup.numberOfItems-1) }
        }
    }
    @objc private func providerChanged() { var p = controller.preferences; p.provider = provider.indexOfSelectedItem == 0 ? "codex" : "claude"; controller.savePreferences(p) }
    private func save() {
        var p = controller.preferences; p.provider = provider.indexOfSelectedItem == 0 ? "codex" : "claude"
        if let value = reading.titleOfSelectedItem, !value.contains("(not offered)") { p.readingModel = value }
        if let value = asking.titleOfSelectedItem, !value.contains("(not offered)") { p.askingModel = value }
        p.tokenLimit = max(1,min(1000000000,Int(tokens.stringValue) ?? 100000)); p.requestLimit = max(1,min(1000000,Int(requests.stringValue) ?? 100)); p.drawings = drawings.state == .on
        controller.savePreferences(p); state.stringValue = "Settings saved"
    }
    private func signIn() {
        // The tool's own terminal sign-in UI owns its token. No shell interpolation or token exchange.
        let script = NSAppleScript(source:"tell application \"Terminal\" to do script \"pi\"")
        var error:NSDictionary?; _ = script?.executeAndReturnError(&error)
        state.stringValue = error == nil ? "In Terminal choose /login → OpenAI Codex, then Refresh models. If pi is absent, run codex login." : "Open Terminal and run pi, then /login → OpenAI Codex (or codex login)."
    }
}
