# Homebrew Cask for VLESS Client, intended for a personal tap
# (e.g. `brew tap dbelokursky/tap && brew install --cask vless-client`).
#
# version and sha256 are rewritten by scripts/update-packaging.sh after the
# release DMG is built; the committed template carries a 0.0.0 version and an
# all-zero sha256 placeholder.
cask "vless-client" do
  version "0.0.0"
  sha256 "0000000000000000000000000000000000000000000000000000000000000000"

  url "https://github.com/dbelokursky/vless-client/releases/download/v#{version}/vless-client_#{version}.dmg",
      verified: "github.com/dbelokursky/vless-client/"
  name "VLESS Client"
  desc "Desktop VLESS/proxy client wrapping sing-box with a JavaFX GUI"
  homepage "https://github.com/dbelokursky/vless-client"

  livecheck do
    url :url
    strategy :github_latest
  end

  app "VLESS Client.app"

  # Builds are not signed/notarized with an Apple Developer certificate yet, so
  # Gatekeeper blocks the first launch. These caveats mirror the README's
  # "Open Anyway" walkthrough. Remove this stanza once the app is notarized.
  caveats <<~EOS
    VLESS Client is not notarized yet, so macOS Gatekeeper blocks the first
    launch. To unblock it (a one-time trip through System Settings):

      1. Launch VLESS Client once and click "Done" (not "Move to Trash").
      2. Open System Settings -> Privacy & Security.
      3. Under Security, click "Open Anyway" next to the "VLESS Client" notice.
      4. Confirm with your password or Touch ID, then click "Open".

    You must repeat this after every update until the app is signed.
  EOS

  # App data lives under ~/Library/Application Support/VlessClient (see
  # MacPlatformPaths in the source tree).
  zap trash: [
    "~/Library/Application Support/VlessClient",
  ]
end
