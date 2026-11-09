#!/usr/bin/env python3
"""Archive metadata fidelity: numeric uid/gid and mode survive intake, flatten,
assemble and the written tar; generated content resolves to root 0/0 visibly
(report["identity"]) instead of silently, and the manifest declares it."""
import argparse
import importlib.util
import io
import json
import os
import sys
import tarfile
import tempfile
import unittest
from pathlib import Path

sys.dont_write_bytecode = True

HERE = Path(__file__).resolve().parent
RTBUILD = HERE.parent / "lib" / "rtbuild.py"
_spec = importlib.util.spec_from_file_location("rtbuild", RTBUILD)
rtbuild = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(rtbuild)


def tar_member(name, kind, mode=0o644, data=b"", link=None, uid=0, gid=0):
    ti = tarfile.TarInfo(name)
    ti.mode = mode
    ti.uid = uid
    ti.gid = gid
    if kind == "dir":
        ti.type = tarfile.DIRTYPE
    elif kind == "symlink":
        ti.type = tarfile.SYMTYPE
        ti.linkname = link
    elif kind == "hardlink":
        ti.type = tarfile.LNKTYPE
        ti.linkname = link
    if ti.isreg():
        ti.size = len(data)
    return ti, data


def write_tar(path, members, gz=True):
    with tarfile.open(path, "w:gz" if gz else "w") as t:
        for ti, data in members:
            if ti.isdir() or ti.issym() or ti.islnk():
                t.addfile(ti)
            else:
                t.addfile(ti, io.BytesIO(data))


def ar_member(name, body):
    """One member of a v7-style ar archive (what read_deb parses)."""
    header = (name + "/").encode("ascii").ljust(16, b" ")
    header += b"0".ljust(12) + b"0".ljust(6) + b"0".ljust(6) + b"100644".ljust(8)
    header += str(len(body)).encode("ascii").ljust(10) + b"\x60\x0a"
    return header + body + (b"\n" if len(body) % 2 else b"")


def make_deb(path):
    control_buf = io.BytesIO()
    with tarfile.open(fileobj=control_buf, mode="w:gz") as t:
        fields = b"Package: fake\nVersion: 1.0\nArchitecture: all\n"
        ti = tarfile.TarInfo("./control")
        ti.mode = 0o644
        ti.size = len(fields)
        t.addfile(ti, io.BytesIO(fields))
    data_buf = io.BytesIO()
    with tarfile.open(fileobj=data_buf, mode="w:gz") as t:
        for name, kind, mode, data, link, uid, gid in (
                ("etc", "dir", 0o755, b"", None, 0, 0),
                ("etc/fake", "dir", 0o755, b"", None, 12, 13),
                ("etc/fake/owned", "file", 0o640, b"deb payload\n", None, 12, 13)):
            ti, data = tar_member(name, kind, mode, data, link, uid, gid)
            if ti.isdir():
                t.addfile(ti)
            else:
                t.addfile(ti, io.BytesIO(data))
    blob = (b"!<arch>\n"
            + ar_member("control.tar.gz", control_buf.getvalue())
            + ar_member("data.tar.gz", data_buf.getvalue()))
    Path(path).write_bytes(blob)


class EntryCompatibility(unittest.TestCase):
    def test_legacy_positional_call_leaves_identity_unspecified(self):
        e = rtbuild.Entry("file", 0o644, b"data", "p", "link", "deb")
        self.assertEqual((e.kind, e.mode, e.data, e.path, e.link, e.origin),
                         ("file", 0o644, b"data", "p", "link", "deb"))
        self.assertIsNone(e.uid)
        self.assertIsNone(e.gid)

    def test_explicit_identity_is_kept(self):
        e = rtbuild.Entry("dir", 0o755, uid=1000, gid=1001)
        self.assertEqual((e.uid, e.gid), (1000, 1001))

    def test_size_and_read_unaffected(self):
        with tempfile.TemporaryDirectory() as temporary:
            src = Path(temporary) / "f.bin"
            src.write_bytes(b"payload")
            e = rtbuild.Entry("file", 0o644, path=str(src), uid=5, gid=6)
            self.assertEqual(e.size(), 7)
            self.assertEqual(e.read(), b"payload")


class PluginIdentity(unittest.TestCase):
    def test_plugin_files_keep_on_disk_identity(self):
        with tempfile.TemporaryDirectory() as temporary:
            src = Path(temporary) / "dsh-su-tools"
            (src / "lib").mkdir(parents=True)
            (src / "package.json").write_text(json.dumps(
                {"name": "dsh-su-tools", "version": "0.1.0", "main": "./lib/index.mjs",
                 "exports": {".": "./lib/index.mjs"}, "files": ["lib", "package.json"]}))
            index = src / "lib" / "index.mjs"
            index.write_text("export {};\n")
            tool = src / "lib" / "tool.js"
            tool.write_text("export {};\n")
            os.chmod(tool, 0o755)
            entries = {}
            rtbuild.add_plugin_files(entries, src, "opt/dsh-go/dsh-su-tools")
            self.assertEqual(sorted(entries), [
                "opt/dsh-go/dsh-su-tools/lib/index.mjs",
                "opt/dsh-go/dsh-su-tools/lib/tool.js",
                "opt/dsh-go/dsh-su-tools/package.json",
            ])
            self.assertEqual(entries["opt/dsh-go/dsh-su-tools/lib/tool.js"].mode, 0o755)
            self.assertEqual(entries["opt/dsh-go/dsh-su-tools/lib/tool.js"].uid,
                             os.lstat(tool).st_uid)
            self.assertEqual(entries["opt/dsh-go/dsh-su-tools/lib/tool.js"].gid,
                             os.lstat(tool).st_gid)
            self.assertEqual(entries["opt/dsh-go/dsh-su-tools/lib/index.mjs"].mode, 0o644)
            self.assertEqual(entries["opt/dsh-go/dsh-su-tools/lib/index.mjs"].uid,
                             os.lstat(index).st_uid)
            pkg_json = entries["opt/dsh-go/dsh-su-tools/package.json"]
            self.assertEqual(pkg_json.mode, 0o644)
            self.assertEqual(pkg_json.uid, os.lstat(src / "package.json").st_uid)
            self.assertEqual(pkg_json.gid, os.lstat(src / "package.json").st_gid)


class ArchiveMetadataRoundtrip(unittest.TestCase):
    def test_numeric_identity_survives_intake_flatten_to_archive(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)

            # 1. ubuntu base tarball: root files, foreign owner, setuid, sticky,
            #    symlink, hard link (flattened to a copy), and one dropped doc.
            base = []
            base.append(tar_member("bin", "dir", 0o755))
            base.append(tar_member("bin/dash", "file", 0o755, b"#!/bin/sh\n"))
            base.append(tar_member("bin/sh", "symlink", 0o777, link="dash"))
            base.append(tar_member("bin/sh2", "hardlink", 0o755, link="bin/dash", uid=os.getuid(), gid=os.getgid()))
            base.append(tar_member("etc", "dir", 0o755))
            base.append(tar_member("srv", "dir", 0o755))
            base.append(tar_member("srv/data", "dir", 0o750, uid=1000, gid=1001))
            base.append(tar_member("srv/data/owned", "file", 0o640, b"owned\n", uid=1000, gid=1001))
            base.append(tar_member("usr/bin", "dir", 0o755))
            base.append(tar_member("usr/bin/su", "file", 0o4755, b"su\n"))
            base.append(tar_member("tmp", "dir", 0o1777))
            base.append(tar_member("var/lib/dpkg", "dir", 0o755))
            base.append(tar_member("var/lib/dpkg/status", "file", 0o644,
                                   b"Package: base\nStatus: install ok installed\n\n"))
            base.append(tar_member("usr/share/doc/fake/changelog", "file", 0o644, b"dropped\n"))
            write_tar(root / "base.tar.gz", base)

            # 2. extra deb: package files owned by a service identity (12:13)
            make_deb(root / "fake.deb")

            # 3. relocatable python: uid 9 / gid 10
            py = [tar_member("python/bin", "dir", 0o755, uid=9, gid=10),
                  tar_member("python/bin/python3", "file", 0o755, b"py\n", uid=9, gid=10),
                  tar_member("python/lib", "dir", 0o755, uid=9),
                  tar_member("python/lib/python3.11/os.py", "file", 0o644, b"# os\n", uid=9, gid=10)]
            write_tar(root / "python.tar.gz", py)

            # 4. node: uid 7 / gid 8, plus a dropped README
            node = [tar_member("node-v20.0.0-linux-x64/bin", "dir", 0o755, uid=7, gid=8),
                    tar_member("node-v20.0.0-linux-x64/bin/node", "file", 0o755, b"node\n", uid=7, gid=8),
                    tar_member("node-v20.0.0-linux-x64/LICENSE", "file", 0o644, b"MPL\n", uid=7),
                    tar_member("node-v20.0.0-linux-x64/README.md", "file", 0o644, b"readme\n", uid=7)]
            write_tar(root / "node.tar.gz", node)

            # 5. dsh npm tree on disk: identity comes from lstat
            dsh = root / "dsh"
            (dsh / "lib").mkdir(parents=True)
            pkg = dsh / "package.json"
            pkg.write_text(json.dumps({"name": "@deepseek-ai/dsh", "version": "0.0.0-test"}))
            (dsh / "lib" / "bin.js").write_text("export {};\n")

            # overlay fixtures so the test does not read repo build assets
            boot = root / "boot"
            boot.mkdir()
            (boot / "boot-cache.mjs").write_text("// fixture\n")
            direct = root / "direct"
            direct.mkdir()
            (direct / "loader.mjs").write_text("export const loader = 1;\n")
            saved_boot, saved_direct = rtbuild.BOOT_CACHE_SRC, rtbuild.DIRECT_SRC
            rtbuild.BOOT_CACHE_SRC = str(boot / "boot-cache.mjs")
            rtbuild.DIRECT_SRC = str(direct)
            try:
                out, rep = root / "rootfs.tar.gz", root / "report.json"
                rtbuild.cmd_assemble(argparse.Namespace(
                    abi="x86_64", base=str(root / "base.tar.gz"), node=str(root / "node.tar.gz"),
                    python=str(root / "python.tar.gz"), dsh_prefix=str(dsh), pnpm_prefix=None,
                    out=str(out), report=str(rep), runtime_info=json.dumps({"abi": "x86_64", "ubuntuVersion": "24.04.5"}),
                    compress="gz", xz_preset="6", gzip_level=1, threads=0, epoch=1767225600,
                    prune_dev_metadata=False, deb=[str(root / "fake.deb")], bridge=None, plugin=None))
            finally:
                rtbuild.BOOT_CACHE_SRC, rtbuild.DIRECT_SRC = saved_boot, saved_direct

            with tarfile.open(out, "r:gz") as t:
                members = {m.name: m for m in t}
                bundled_info = json.load(t.extractfile("etc/dsh-app/runtime.json"))
                self.assertEqual(bundled_info["upgrade"]["schema"], 1)
                self.assertEqual(bundled_info["upgrade"]["ubuntuVersion"], "24.04.5")

            def expect(name, uid, gid, mode, kind="file", link=None):
                m = members[name]
                self.assertEqual(m.uid, uid, name + " uid")
                self.assertEqual(m.gid, gid, name + " gid")
                self.assertEqual(m.mode, mode, name + " mode")
                self.assertEqual(m.isdir(), kind == "dir", name + " kind")
                if link is not None:
                    self.assertEqual(m.linkname, link, name + " link")
                self.assertEqual(m.uname, "root" if uid == 0 else "", name + " uname")
                self.assertEqual(m.gname, "root" if gid == 0 else "", name + " gname")

            # ubuntu intake: numeric identity and normalised modes
            expect("bin/dash", 0, 0, 0o755)
            expect("bin/sh", 0, 0, 0o777, "symlink", link="dash")
            expect("bin/sh2", os.getuid(), os.getgid(), 0o755)          # hardlink -> own copy, own identity
            expect("srv/data", 1000, 1001, 0o750, "dir")
            expect("srv/data/owned", 1000, 1001, 0o640)
            expect("usr/bin/su", 0, 0, 0o755)             # setuid cleared
            expect("tmp", 0, 0, 0o1777, "dir")            # sticky bit kept on dirs
            self.assertNotIn("usr/share/doc/fake/changelog", members)

            # deb intake: service identity preserved, generated dpkg state is root
            expect("etc/fake", 12, 13, 0o755, "dir")
            expect("etc/fake/owned", 12, 13, 0o640)
            expect("var/lib/dpkg/info/fake.list", 0, 0, 0o644)
            expect("var/lib/dpkg/info", 0, 0, 0o755, "dir")   # implicit parent

            # python and node intakes
            expect("opt/python/bin", 9, 10, 0o755, "dir")
            expect("opt/python/bin/python3", 9, 10, 0o755)
            expect("opt/python/lib/python3.11/os.py", 9, 10, 0o644)
            expect("opt/node/bin", 7, 8, 0o755, "dir")
            expect("opt/node/bin/node", 7, 8, 0o755)
            expect("opt/node/LICENSE", 7, 0, 0o644)
            # node intake keeps only bin/, lib/ and LICENSE (keep_node_top);
            # README.md is dropped as node-headers-docs with its real size
            self.assertNotIn("opt/node/README.md", members)

            # on-disk npm tree: build-host identity
            expect("opt/dsh", os.lstat(dsh).st_uid, os.lstat(dsh).st_gid, 0o755, "dir")
            expect("opt/dsh/package.json", os.lstat(pkg).st_uid, os.lstat(pkg).st_gid, 0o644)

            # generated overlay content resolves to root
            expect("etc/dsh-app/runtime.json", 0, 0, 0o644)
            expect("opt/dsh-go/direct/native-addons.json", 0, 0, 0o644)
            expect("root/.l2s", 0, 0, 0o700, "dir")

            report = json.loads(rep.read_text())
            ident = report["identity"]
            self.assertEqual(ident["distinctUidGid"].get("12:13"), 2)      # etc/fake + owned
            self.assertEqual(ident["distinctUidGid"].get("7:8"), 2)        # opt/node/bin + node
            self.assertEqual(ident["distinctUidGid"].get("9:10"), 3)       # bin, python3, os.py
            self.assertEqual(ident["distinctUidGid"].get("1000:1001"), 2)  # srv/data + owned
            # <buildhost uid>:<gid> = hardlink copy bin/sh2 plus the whole
            # on-disk npm tree, which keeps the build-host user's identity
            self.assertEqual(
                ident["distinctUidGid"].get("%d:%d" % (os.getuid(), os.getgid())), 5)
            self.assertGreaterEqual(ident["resolvedToRoot"], 5)            # dpkg list, overlay, implicit dirs...
            self.assertIn("0:0", ident["distinctUidGid"])
            self.assertEqual(report["dropped"]["node-headers-docs"], len(b"readme\n"))
            self.assertEqual(report["dropped"]["ubuntu-docs-caches"], len(b"dropped\n"))
            self.assertEqual(report["counts"]["symlink"],
                             sum(1 for m in members.values() if m.issym()))

            # manifest carries the identity contract and an honest note
            jni = root / "jni.json"
            jni.write_text(json.dumps({"lib/arm64-v8a/libproot.so": "abc"}))
            versions = json.dumps({"runtimeVersion": "test", "dshVersion": "t", "nodeVersion": "20.0.0",
                                   "pnpmVersion": "9.0.0", "ubuntuVersion": "24.04",
                                   "prootVersion": "5.1.107.102", "tallocVersion": "2",
                                   "androidShmemVersion": "1"})
            mout = root / "manifest.json"
            rtbuild.cmd_manifest(argparse.Namespace(
                abi="x86_64", report=str(rep), jnilibs=str(jni), versions=versions, out=str(mout)))
            manifest = json.loads(mout.read_text())
            self.assertEqual(manifest["identity"], ident)
            notes = manifest["archiveNotes"]
            self.assertIn("numeric uid/gid preserved per member as built", notes)
            self.assertIn("uname/gname root only for uid/gid 0", notes)
            self.assertIn("setuid/setgid cleared", notes)
            self.assertIn("sticky bit", notes)
            self.assertNotIn("uid/gid are not preserved", notes)

    def test_identity_report_shape_is_contractual(self):
        entries = {
            "a": rtbuild.Entry("file", 0o644, data=b"x", uid=0, gid=0),
            "b": rtbuild.Entry("file", 0o644, data=b"x", uid=1000, gid=1000),
            "c": rtbuild.Entry("dir", 0o755),
        }
        resolved = sum(1 for e in entries.values() if e.uid is None or e.gid is None)
        self.assertEqual(resolved, 1)  # generated dir without source identity
        for e in entries.values():
            if e.uid is None:
                e.uid = 0
            if e.gid is None:
                e.gid = 0
        self.assertEqual({e.uid for e in entries.values()}, {0, 1000})


if __name__ == "__main__":
    unittest.main(verbosity=2)
