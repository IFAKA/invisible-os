# Homebrew formula for the `invisible` command. NOT published: there is no tap yet.
# Head only (builds from the git repo's master branch). When a release exists, replace `head` with:
#   url "https://github.com/IFAKA/invisible-os/archive/refs/tags/vX.Y.Z.tar.gz"
#   sha256 "<shasum -a 256 of that file>"
class Invisible < Formula
  desc "Control your phone with one earbud button; pair a Mac, make programs"
  homepage "https://github.com/IFAKA/invisible-os"
  head "https://github.com/IFAKA/invisible-os.git", branch: "master"
  license :cannot_represent

  depends_on "python@3"
  depends_on "tmux" => :recommended

  def install
    # The command finds its checkout by its own real path, so keep the whole tree together in libexec.
    libexec.install Dir["*"]
    bin.install_symlink libexec/"bin/invisible"
  end

  def caveats
    <<~EOS
      Next: invisible pair
      Then scan the QR code with the phone camera.
    EOS
  end

  test do
    assert_match "invisible pair", shell_output("#{bin}/invisible")
  end
end
