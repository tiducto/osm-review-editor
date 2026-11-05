package eu.tiducto.osmreview.server.replication

import com.geodesk.feature.FeatureLibrary
import eu.tiducto.osmreview.rules.GolHeader
import eu.tiducto.osmreview.rules.GolMirror
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.system.exitProcess

object ClipperCli {
    @JvmStatic
    fun main(args: Array<String>) {
        val opts = parse(args)
        when {
            "header" in opts -> header(opts.getValue("header"))
            "missing" in opts -> missing(Path.of(opts.getValue("missing").single()))
            "hold" in opts -> hold(Path.of(opts.getValue("hold")[0]), opts.getValue("hold").getOrNull(1)?.toLong() ?: 60)
            else -> clip(opts)
        }
    }

    private fun header(files: List<String>) {
        for (f in files) {
            val h = GolHeader.read(Path.of(f))
            if (h == null) {
                println("$f: unreadable")
                continue
            }
            println("$f revision=${h.revision} timestamp=${h.timestamp} url=${h.url ?: ""}")
        }
    }

    private fun missing(file: Path) {
        FeatureLibrary(file).use { gol ->
            val ways = gol.select("w[geodesk:missing_nodes]").count()
            val relations = gol.select("r[geodesk:missing_members]").count()
            println("$file missing_nodes_ways=$ways missing_members_relations=$relations")
        }
    }

    private fun hold(file: Path, seconds: Long) {
        FeatureLibrary(file).use { gol ->
            println("holding $file open (${gol.select("n").first()?.id() ?: 0}) for ${seconds}s")
            System.out.flush()
            Thread.sleep(seconds * 1000)
        }
    }

    private fun clip(opts: Map<String, List<String>>) {
        fun one(k: String) = opts[k]?.single()
        val gol = Path.of(one("gol") ?: usage())
        val out = Path.of(one("out") ?: usage())
        val config = ReplicationConfig(
            enabled = true,
            sourceUrl = one("source") ?: ReplicationConfig.DEFAULT_SOURCE,
            dir = out,
            batchSize = one("batch")?.toInt() ?: 60,
            area = one("area"),
            bufferMeters = one("buffer")?.toDouble() ?: 2000.0,
            generator = "OSM Review Editor clipper",
        )
        val max = one("max")?.toLong() ?: Long.MAX_VALUE
        val userAgent = "OSMReviewEditor/clipper-cli (+${one("contact") ?: "local"})"
        GolMirror(out.resolve("no-sidecar"), gol).use { mirror ->
            check(mirror.reloadIfChanged()) { mirror.status().message ?: "Cannot open $gol" }
            val service = ReplicationService(config, mirror, HttpReplicationSource(config.sourceUrl, userAgent))
            val reference = GolHeader.read(gol)?.timestamp
            var report = runBlocking { service.runOnce(if (max == Long.MAX_VALUE) max else 0) }
            if (max != Long.MAX_VALUE && reference != null) {
                var past = 0L
                var guard = 0
                while (past < max && guard++ < 100) {
                    val head = report.head ?: break
                    val behind = if (head.timestamp.isAfter(reference)) 0L else java.time.Duration.between(head.timestamp, reference).toMinutes() + 1
                    val step = behind + max - past
                    val r = runBlocking { service.runOnce(step) }
                    if (r.sequences == 0) break
                    report = RunReport(report.sequences + r.sequences, report.kept + r.kept, report.dropped + r.dropped, r.head, report.rebased || r.rebased)
                    past = ReplicationDir(out).let { d -> (d.head()?.sequence ?: 0) - firstAfter(d, reference) + 1 }
                }
            }
            val s = service.status()
            println(
                "clipped sequences=${report.sequences} kept=${report.kept} dropped=${report.dropped} " +
                    "head=${report.head?.sequence} headTimestamp=${report.head?.timestamp} first=${s.firstSequence} " +
                    "rebased=${report.rebased} area=${s.area} source=${s.sourceSequence}",
            )
        }
    }

    private fun firstAfter(dir: ReplicationDir, reference: java.time.Instant): Long {
        var seq = dir.head()?.sequence ?: return 0
        while (true) {
            val prev = dir.state(seq - 1) ?: return seq
            if (!prev.timestamp.isAfter(reference)) return seq
            seq--
        }
    }

    private fun usage(): Nothing {
        System.err.println(
            "usage: ClipperCli --gol <file.gol> --out <replication dir> [--max N sequences past the GOL timestamp] [--source URL] [--area FILE|mirror:CZ] [--buffer M] [--batch N]\n" +
                "       ClipperCli --header <file.gol>...\n" +
                "       ClipperCli --missing <file.gol>\n" +
                "       ClipperCli --hold <file.gol> [seconds]",
        )
        exitProcess(2)
    }

    private fun parse(args: Array<String>): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        var key: String? = null
        for (a in args) {
            if (a.startsWith("--")) {
                key = a.removePrefix("--")
                out.getOrPut(key) { ArrayList() }
            } else {
                out.getOrPut(key ?: usage()) { ArrayList() } += a
            }
        }
        return out
    }
}
