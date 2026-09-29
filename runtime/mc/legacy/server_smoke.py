"""The shipped jars on real Forge dedicated servers, with ServerSmoke armed -- one per version named.

    python runtime/mc/legacy/server_smoke.py [--java <java 8>] 1.12.2 1.10.2 1.8.9

Build the jars first (`./gradlew singleJar languageJar :CrystalGraphics:singleJar`). Each version gets a
Forge server installed once under build/legacy-server/<version>, with MixinBooter 11.17 and the four jars
in mods/, and Mojang's EULA accepted for that directory. A dev run's merged classpath cannot show what a
production server lacks -- client classes, a walkable code source, an exit FML does not trap -- which is
what this is for. The verdict is each server's smoke.txt; an absent file is a failure.
"""
import json, os, shutil, subprocess, sys, urllib.request

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
FORGE = "https://maven.minecraftforge.net/net/minecraftforge/forge/"
MIXINBOOTER = "https://cdn.modrinth.com/data/G1ckZuWK/versions/6jJK1B2d/%21mixinbooter-11.17.jar"
JARS = ["build/libs/crystalgui-1.0.0.jar", "build/libs/crystalgui-language-1.0.0.jar",
        "CrystalGraphics/build/libs/crystalgraphics-1.0.0.jar",
        "CrystalGraphics/build/libs/crystalgraphics-joml-1.0.0.jar"]
sys.stdout.reconfigure(errors="replace")


def fetch(url):
    return urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "server-smoke"}),
                                  timeout=180).read()


def install(java, mc, d):
    """Forge's own installer, once; answers the server jar's name."""
    jars = [f for f in os.listdir(d) if f.startswith("forge-") and f.endswith(".jar") and "installer" not in f]
    if jars:
        return jars[0]
    promos = json.loads(fetch("https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json"))["promos"]
    build = promos.get(mc + "-recommended") or promos.get(mc + "-latest")
    for coord in (f"{mc}-{build}", f"{mc}-{build}-{mc}"):  # 1.8.9's carries the branch suffix
        try:
            open(os.path.join(d, "installer.jar"), "wb").write(fetch(f"{FORGE}{coord}/forge-{coord}-installer.jar"))
            break
        except Exception:
            continue
    subprocess.run([java, "-jar", "installer.jar", "--installServer"], cwd=d, check=True, capture_output=True)
    return [f for f in os.listdir(d) if f.startswith("forge-") and f.endswith(".jar") and "installer" not in f][0]


def smoke(java, mc):
    d = os.path.join(ROOT, "build", "legacy-server", mc)
    mods = os.path.join(d, "mods")
    os.makedirs(mods, exist_ok=True)
    server = install(java, mc, d)
    for f in os.listdir(mods):
        os.remove(os.path.join(mods, f))
    for jar in JARS:
        shutil.copy2(os.path.join(ROOT, jar), mods)
    open(os.path.join(mods, "!mixinbooter-11.17.jar"), "wb").write(fetch(MIXINBOOTER))
    open(os.path.join(d, "eula.txt"), "w").write("eula=true\n")
    open(os.path.join(d, "server.properties"), "w").write(
        "server-port=25591\nlevel-type=FLAT\nonline-mode=false\n")
    report = os.path.join(d, "smoke.txt")
    if os.path.exists(report):
        os.remove(report)
    try:
        subprocess.run([java, "-Xmx2G", "-Dcrystalgui.server.smoke=true",
                        "-Dcrystalgui.server.smoke.report=" + report, "-jar", server, "nogui"],
                       cwd=d, capture_output=True, timeout=400)
    except subprocess.TimeoutExpired:
        pass
    verdict = open(report, encoding="utf-8").read() if os.path.exists(report) else "NO REPORT: the smoke never ran\n"
    print(f"== {mc} ==\n{verdict}")
    return verdict.startswith("PASS")


def main(args):
    java = "java"
    if args[:1] == ["--java"]:
        java, args = args[1], args[2:]
    results = [smoke(java, mc) for mc in (args or ["1.12.2"])]
    sys.exit(0 if all(results) else 1)


if __name__ == "__main__":
    main(sys.argv[1:])
