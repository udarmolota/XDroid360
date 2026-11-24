package com.udarmolota.xdroid360;

import android.content.Context;
import android.os.ParcelFileDescriptor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs a recipe's steps in order (SD1/toolchain/RECIPE_FORMAT_DRAFT.md): substitutes the
 * variables, starts the tools from nativeLibraryDir as child processes (never through a shell),
 * reports progress, and supports cancel and resume. A finished step leaves a marker; on resume
 * the marked steps are skipped, and extract and compile steps also resume per file.
 */
final class RecipeRunner {
    interface Listener {
        /** overall 0..1; step is the step's label, detail its own progress ("12 / 203"). */
        void onProgress(double overall, String step, String detail);
    }

    private final Context context;
    private final BuildJob job;
    private final Recipe recipe;
    private final Listener listener;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final List<Process> running = Collections.synchronizedList(new ArrayList<>());
    private final Recipe.Vars vars = new Recipe.Vars();
    private final Map<String, List<String>> objects = new HashMap<>();
    private final File work, logs;
    private final int jobs;
    private double weightDone, weightTotal;
    private String stepLabel = "";

    RecipeRunner(Context context, BuildJob job, Recipe recipe, Listener listener) {
        this.context = context;
        this.job = job;
        this.recipe = recipe;
        this.listener = listener;
        this.work = job.work(context);
        this.logs = new File(work, "logs");
        this.jobs = jobs(recipe);
        vars.put("TOOLS", Toolchain.tools(context).getPath());
        vars.put("KIT", Toolchain.kitDir(context).getPath());
        vars.put("PROFILE", Toolchain.profileDir(context, recipe.profileId).getPath());
        vars.put("GAME", job.gameRoot(context).getPath());
        vars.put("WORK", work.getPath());
        vars.put("INSTANCE", job.internal(context).getPath());
        vars.put("JOBS", String.valueOf(jobs));
        vars.putAll(recipe.json.optJSONObject("vars"));
    }

    void cancel() {
        cancelled.set(true);
        synchronized (running) {
            for (Process p : running) p.destroyForcibly();
        }
    }

    /** Builds the library and installs it in the instance's internal folder. */
    void run() throws Recipe.Failure {
        JSONArray steps = recipe.json.optJSONArray("steps");
        for (int i = 0; i < steps.length(); i++) weightTotal += Math.max(0, steps.optJSONObject(i).optDouble("weight", 1));
        if (weightTotal <= 0) weightTotal = 1;
        // An update over a kept build (keepForUpdate): its steps stand only while the profile's
        // files they read are the same.
        File print = new File(work, "update.fingerprint");
        if (print.isFile()) {
            try {
                String was = new String(Files.readAllBytes(print.toPath()), StandardCharsets.UTF_8);
                if (!was.equals(fingerprint())) {
                    android.util.Log.i("XDroid360", "update: the profile's game-code inputs changed, building all again");
                    Instance.deleteTree(work);
                }
            } catch (IOException e) {
                Instance.deleteTree(work);
            }
        }
        // The steps that take the profile's own files always run again: the runtime they link
        // comes with the launcher, so a marker of theirs may be from another one.
        for (int i = firstProfileStep(); i < steps.length(); i++) {
            JSONObject step = steps.optJSONObject(i);
            if (!step.optBoolean("keeps_game")) Instance.deleteTree(marker(step));
        }
        // So do the steps marked "rerun" (the shader library: a new launcher may bring a better
        // translator), but only if the tool that made what they made has changed since, or what
        // they made is gone (rerunCurrent).
        for (int i = 0; i < steps.length(); i++) {
            JSONObject step = steps.optJSONObject(i);
            if (step.optBoolean("rerun") && !rerunCurrent(step)) Instance.deleteTree(marker(step));
        }
        //noinspection ResultOfMethodCallIgnored
        new File(work, "tmp").mkdirs();
        //noinspection ResultOfMethodCallIgnored
        logs.mkdirs();
        for (int i = 0; i < steps.length(); i++) {
            JSONObject step = steps.optJSONObject(i);
            String id = step.optString("id");
            stepLabel = step.optString("label", stepLabel);
            double weight = Math.max(0, step.optDouble("weight", 1));
            try {
                if ("each".equals(step.optString("kind"))) planObjects(step); // for "@objects:" even when skipped
                if (!marker(step).isFile() && applies(step)) {
                    for (String dir : strings(step.optJSONArray("creates"))) {
                        //noinspection ResultOfMethodCallIgnored
                        new File(vars.text(dir)).mkdirs();
                    }
                    runStep(step, weight);
                    writeMarker(step);
                }
            } catch (Recipe.Failure f) {
                job.failedStep = id;
                if ("cancelled".equals(f.code) || cancelled.get()) throw new Recipe.Failure("cancelled", "");
                String message = step.optString("message");
                if (Recipe.isCode(message) && !"not_enough_space".equals(f.code)) throw new Recipe.Failure(message, f.getMessage());
                throw f;
            } catch (IOException e) {
                job.failedStep = id;
                if (cancelled.get()) throw new Recipe.Failure("cancelled", "");
                throw new Recipe.Failure(noSpace(e) ? "not_enough_space" : defaultCode(step), e.toString());
            }
            weightDone += weight;
            report(0, "");
        }
        install();
    }

    // ---------------------------------------------------------------- steps

    private void runStep(JSONObject step, double weight) throws Recipe.Failure, IOException {
        switch (step.optString("kind")) {
            case "extract": extract(step, weight); break;
            case "run": runTool(step, weight); break;
            case "each": each(step, weight); break;
            case "write": write(step); break;
            case "verify": verify(step); break;
            default: throw new Recipe.Failure("tool_failed", "unknown step kind " + step.optString("kind"));
        }
    }

    /**
     * The disc's files into ${GAME}/game. A zipped source is inflated into ${GAME}/.source first
     * (the extractors need a seekable file): DAO's disc image whole, SoD's packages one at a
     * time (inflate, extract, delete), so the scratch copy needs at most its largest package.
     * The scratch folder goes when the step is done.
     */
    private void extract(JSONObject step, double weight) throws Recipe.Failure, IOException {
        File to = new File(vars.text(step.optString("to", "${GAME}")));
        File scratch = new File(to, ".source");
        GameSource source = GameSource.detect(context, job.source, job.pick);
        if ("xiso".equals(step.optString("format"))) {
            if (source.zipped) extractZippedIso(source, to, scratch, weight);
            else extractIso(source, to, weight);
        } else if ("stfs".equals(step.optString("format"))) {
            extractPackages(step, source, to, scratch, weight);
        } else {
            throw new Recipe.Failure("extract_failed", "unknown format " + step.optString("format"));
        }
        Instance.deleteTree(scratch);
    }

    private void extractIso(GameSource source, File to, double weight) throws Recipe.Failure, IOException {
        try (ParcelFileDescriptor pfd = GameSource.open(context, source.uri);
             FileInputStream in = new FileInputStream(pfd.getFileDescriptor());
             FileChannel channel = in.getChannel()) {
            Xiso iso = new Xiso(channel);
            // Both games keep the disc's files in ${GAME}/game (the format's layout).
            iso.extract(new File(to, "game"), (done, total) -> {
                report(total > 0 ? weight * done / total : 0, mb(done) + " / " + mb(total));
                return !cancelled.get();
            });
        }
    }

    private void extractZippedIso(GameSource source, File to, File scratch, double weight)
            throws Recipe.Failure, IOException {
        File image = new File(scratch, "game.iso");
        try (ZipSource zip = ZipSource.open(context, source.uri)) {
            java.util.zip.ZipEntry e = zip.entry(source.zipIso);
            if (e == null) throw new Recipe.Failure("source_unreadable", "no " + source.zipIso + " in the zip any more");
            long all = Math.max(1, e.getSize()) * 2; // inflating, then extracting about as much
            zip.inflate(e, image, (done, total) -> {
                report(weight * done / all, mb(done) + " / " + mb(e.getSize()));
                return !cancelled.get();
            });
            try (FileInputStream in = new FileInputStream(image); FileChannel channel = in.getChannel()) {
                Xiso iso = new Xiso(channel);
                // Before 6 GB of extracting: is it the version the profile is made for?
                Xiso.Entry xex = iso.find("default.xex");
                JSONArray accepted = GameSource.xexHashes(recipe);
                if (xex == null) throw new Recipe.Failure("unknown_title", "the disc image has no default.xex");
                if (accepted != null && !contains(accepted, iso.sha256(xex))) {
                    throw new Recipe.Failure("unsupported_game_version", "default.xex isn't one the profile accepts");
                }
                iso.extract(new File(to, "game"), (done, total) -> {
                    report(weight * (e.getSize() + (double) done / Math.max(1, total) * e.getSize()) / all,
                            mb(done) + " / " + mb(total));
                    return !cancelled.get();
                });
            }
        }
    }

    private void extractPackages(JSONObject step, GameSource source, File to, File scratch, double weight)
            throws Recipe.Failure, IOException {
        if (!StfsExtractor.available()) throw new Recipe.Failure("extract_failed", "no STFS extractor in this launcher");
        List<GameSource.Package> packages = new ArrayList<>(source.packages);
        packages.sort((a, b) -> a.key().compareTo(b.key()));
        long total = 0, before = 0;
        for (GameSource.Package p : packages) total += p.size;
        // A zipped package is inflated, then extracted: twice its size in progress.
        final long all = Math.max(1, total) * (source.zipped ? 2 : 1);
        try (ZipSource zip = source.zipped ? ZipSource.open(context, source.uri) : null) {
            for (int n = 0; n < packages.size(); n++) {
                GameSource.Package p = packages.get(n);
                File done = new File(job.keptMarkers(context), step.optString("id") + "." + n);
                if (!done.isFile()) {
                    final long base = before * (source.zipped ? 2 : 1);
                    File file = null;
                    if (zip != null) {
                        java.util.zip.ZipEntry e = zip.entry(p.entry);
                        if (e == null) throw new Recipe.Failure("source_unreadable", "no " + p.entry + " in the zip any more");
                        file = new File(scratch, n + ".stfs");
                        zip.inflate(e, file, (d, t) -> {
                            report(weight * (base + d) / all, mb(base + d) + " / " + mb(all));
                            return !cancelled.get();
                        });
                    }
                    final long extractBase = base + (zip != null ? p.size : 0);
                    try (ParcelFileDescriptor pfd = file != null
                            ? ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                            : GameSource.open(context, p.uri)) {
                        int code = StfsExtractor.nativeExtract(pfd.getFd(), to.getPath(), (d, t) -> {
                            report(weight * (extractBase + d) / all, mb(extractBase + d) + " / " + mb(all));
                            return !cancelled.get();
                        });
                        if (code != 0) {
                            String c = code < StfsExtractor.ERRORS.length ? StfsExtractor.ERRORS[code] : "extract_failed";
                            throw new Recipe.Failure(c, "STFS package " + p.key() + ": code " + code);
                        }
                    }
                    if (file != null) //noinspection ResultOfMethodCallIgnored
                        file.delete();
                    touch(done);
                }
                before += p.size;
            }
        }
    }

    private static boolean contains(JSONArray hashes, String sha) {
        for (int i = 0; i < hashes.length(); i++) if (hashes.optString(i).equalsIgnoreCase(sha)) return true;
        return false;
    }

    private void runTool(JSONObject step, double weight) throws Recipe.Failure, IOException {
        List<String> argv = command(step, step.optJSONArray("args"), null);
        File cwd = step.has("cwd") ? new File(vars.text(step.optString("cwd"))) : work;
        Pattern progress = step.has("progress") ? Pattern.compile(step.optString("progress")) : null;
        int code = exec(argv, cwd, new File(logs, step.optString("id") + ".log"), line -> {
            if (progress == null) return;
            Matcher m = progress.matcher(line);
            if (m.find()) {
                try {
                    // One group: a percentage; two: done and total ("progress 10/460").
                    double value = m.groupCount() >= 2
                            ? 100 * Double.parseDouble(m.group(1)) / Math.max(1, Double.parseDouble(m.group(2)))
                            : Double.parseDouble(m.group(1));
                    double pct = Math.min(100, Math.max(0, value));
                    report(weight * pct / 100, String.format(java.util.Locale.ROOT, "%.0f%%", pct));
                } catch (NumberFormatException ignored) {
                }
            }
        });
        if (code != 0) throw new Recipe.Failure(defaultCode(step), step.optString("id") + " exited with " + code);
    }

    /** One step's inputs, in the order "@objects:" lists their objects. */
    private final Map<String, List<File>> inputs = new HashMap<>();

    private void planObjects(JSONObject step) throws Recipe.Failure {
        JSONObject in = step.optJSONObject("inputs");
        List<File> files = new ArrayList<>();
        if (in != null && in.has("glob")) files.addAll(glob(vars.text(in.optString("glob"))));
        if (in != null) for (String plus : strings(in.optJSONArray("plus"))) files.add(new File(vars.text(plus)));
        File dir = objectsDir(step);
        List<String> out = new ArrayList<>();
        File[] kept = marker(step).isFile() ? dir.listFiles((d, n) -> n.endsWith(".o")) : null;
        if (kept != null && kept.length > 0) {
            // A step kept from an earlier build (keepForUpdate): its sources are gone, its objects
            // are what the link takes.
            java.util.Arrays.sort(kept);
            for (File o : kept) out.add(o.getPath());
        } else {
            for (File f : files) out.add(new File(dir, objectName(f)).getPath());
        }
        inputs.put(step.optString("id"), files);
        objects.put(step.optString("id"), out);
    }

    private void each(JSONObject step, double weight) throws Recipe.Failure, IOException {
        String id = step.optString("id");
        List<File> files = new ArrayList<>(inputs.get(id));
        File dir = objectsDir(step);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File stepLogs = new File(logs, id);
        //noinspection ResultOfMethodCallIgnored
        stepLogs.mkdirs();
        JSONObject hints = step.optJSONObject("cost_hints");
        Map<File, Double> cost = new HashMap<>();
        double costTotal = 0;
        for (File f : files) {
            double c = hints != null && hints.has(f.getName()) ? hints.optDouble(f.getName(), 1) : 1;
            cost.put(f, c);
            costTotal += c;
        }
        if ("longest_first".equals(step.optString("schedule"))) {
            files.sort((a, b) -> {
                int byHint = Double.compare(cost.get(b), cost.get(a));
                return byHint != 0 ? byHint : Long.compare(b.length(), a.length());
            });
        }
        // Resume: an object that exists is done (each is written to .tmp and renamed).
        AtomicLong doneCount = new AtomicLong();
        double[] doneCost = { 0 };
        List<File> todo = new ArrayList<>();
        for (File f : files) {
            if (new File(dir, objectName(f)).isFile()) {
                doneCount.incrementAndGet();
                doneCost[0] += cost.get(f);
            } else {
                todo.add(f);
            }
        }
        final double total = costTotal;
        final int count = files.size();
        boolean stopOnFailure = !"continue".equals(step.optString("fail", "stop"));
        AtomicReference<Recipe.Failure> failure = new AtomicReference<>();
        // Up to a job per core, each started only when the memory it is expected to take is free
        // (MemAvailable less the reserve; the recipe's "jobs"), re-checked a moment after the
        // previous start (a compile's memory grows over its first seconds) and whenever a job ends.
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(jobs, Math.max(1, todo.size()))));
        List<Future<?>> futures = new ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger active = new java.util.concurrent.atomic.AtomicInteger();
        long lastStart = 0;
        for (File f : todo) {
            long need = estimateMb(f);
            while (!cancelled.get() && !(stopOnFailure && failure.get() != null)) {
                long since = System.currentTimeMillis() - lastStart;
                int now = active.get();
                if (now == 0 || (now < jobs && since >= ADMIT_SETTLE_MS && memAvailableMb() - reserveMb() >= need)) break;
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    cancel();
                    break;
                }
            }
            if (cancelled.get() || (stopOnFailure && failure.get() != null)) break;
            active.incrementAndGet();
            lastStart = System.currentTimeMillis();
            futures.add(pool.submit(() -> {
              try {
                if (cancelled.get() || (stopOnFailure && failure.get() != null)) return;
                File obj = new File(dir, objectName(f));
                File tmp = new File(obj.getPath() + ".tmp");
                Map<String, String> extra = new HashMap<>();
                extra.put("IN", f.getPath());
                extra.put("OBJ", tmp.getPath());
                try {
                    List<String> argv = command(step, step.optJSONArray("args"), extra);
                    int code = exec(argv, work, new File(stepLogs, f.getName() + ".log"), null);
                    if (code != 0 || !tmp.isFile()) {
                        //noinspection ResultOfMethodCallIgnored
                        tmp.delete();
                        if (!cancelled.get()) {
                            failure.compareAndSet(null, new Recipe.Failure(defaultCode(step), f.getName() + " exited with " + code));
                            if (stopOnFailure) cancelProcesses();
                        }
                        return;
                    }
                    if (!tmp.renameTo(obj)) throw new IOException("can't rename " + tmp);
                    synchronized (doneCost) {
                        doneCost[0] += cost.get(f);
                        long n = doneCount.incrementAndGet();
                        report(weight * (total > 0 ? doneCost[0] / total : (double) n / count), n + " / " + count);
                    }
                } catch (Recipe.Failure e) {
                    failure.compareAndSet(null, e);
                } catch (IOException e) {
                    failure.compareAndSet(null, new Recipe.Failure(noSpace(e) ? "not_enough_space" : defaultCode(step), e.toString()));
                }
              } finally {
                active.decrementAndGet();
              }
            }));
        }
        pool.shutdown();
        try {
            for (Future<?> fu : futures) fu.get();
            pool.awaitTermination(1, TimeUnit.MINUTES);
        } catch (InterruptedException | java.util.concurrent.ExecutionException e) {
            cancel();
            throw new Recipe.Failure("cancelled", e.toString());
        } finally {
            // Cancel: the jobs that were killed leave .tmp files.
            File[] left = dir.listFiles((d, n) -> n.endsWith(".tmp"));
            if (left != null) for (File t : left) //noinspection ResultOfMethodCallIgnored
                t.delete();
        }
        if (cancelled.get()) throw new Recipe.Failure("cancelled", "");
        if (failure.get() != null) throw failure.get();
    }

    private void write(JSONObject step) throws Recipe.Failure, IOException {
        File path = new File(vars.text(step.optString("path")));
        String text = step.has("from")
                ? vars.text(new String(Files.readAllBytes(new File(vars.text(step.optString("from"))).toPath()), StandardCharsets.UTF_8))
                : vars.text(step.optString("text"));
        //noinspection ResultOfMethodCallIgnored
        path.getParentFile().mkdirs();
        Files.write(path.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private void verify(JSONObject step) throws Recipe.Failure, IOException {
        JSONObject files = step.optJSONObject("files");
        if (files == null) return;
        for (Iterator<String> it = files.keys(); it.hasNext(); ) {
            String path = it.next();
            File f = new File(vars.text(path));
            if (!f.isFile()) throw new Recipe.Failure(defaultCode(step), f + " is missing");
            String sha = sha256(f);
            boolean ok = false;
            for (String accepted : strings(files.optJSONArray(path))) ok |= accepted.equalsIgnoreCase(sha);
            if (!ok) throw new Recipe.Failure(defaultCode(step), f.getName() + " has SHA-256 " + sha);
        }
    }

    /** Checks the library's ABI note, then moves it into the instance and drops the scratch files. */
    private void install() throws Recipe.Failure {
        JSONObject result = recipe.json.optJSONObject("result");
        if (result == null) throw new Recipe.Failure("link_failed", "the recipe has no result");
        File library = new File(vars.text(result.optString("library")));
        File target = new File(vars.text(result.optString("install", "${INSTANCE}/libgame.so")));
        if (!library.isFile()) throw new Recipe.Failure("link_failed", "no " + library.getName() + " after the link");
        try {
            int abi = ElfNote.abi(library);
            if (abi != recipe.abi()) throw new Recipe.Failure("abi_mismatch", "the library is for ABI " + abi + ", the recipe says " + recipe.abi());
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            if (!library.renameTo(target)) throw new IOException("can't move the library to " + target);
        } catch (IOException e) {
            throw new Recipe.Failure(noSpace(e) ? "not_enough_space" : "link_failed", e.toString());
        }
        List<File> keep = new ArrayList<>();
        for (String k : strings(result.optJSONArray("keep"))) keep.add(new File(vars.text(k)));
        keepForUpdate(keep);
        File[] children = work.listFiles();
        if (children != null) {
            for (File c : children) if (!keep.contains(c)) Instance.deleteTree(c);
        }
    }

    /**
     * What an update of the game (InstanceActions.update) can reuse: the steps before the first
     * one that takes the profile's own files (include/, lib/: the runtime, which a new launcher
     * changes) only depend on the game and the recipe, so their markers and compiled objects
     * stay. The translated sources go (the objects are what the link takes), the headers stay
     * for build-info. A fingerprint of what those steps read from the profile (xr/, include/,
     * the recipe's vars) voids them when that changes (run()).
     */
    /** The index of the first step that takes the profile's own files (include/, lib/). */
    private int firstProfileStep() {
        JSONArray steps = recipe.json.optJSONArray("steps");
        for (int i = 0; i < steps.length(); i++) {
            // (org.json writes "/" as "\/".)
            String text = steps.optJSONObject(i).toString().replace("\\/", "/");
            if (text.contains("${PROFILE}/include") || text.contains("${PROFILE}/lib")) return i;
        }
        return steps.length();
    }

    private void keepForUpdate(List<File> keep) {
        JSONArray steps = recipe.json.optJSONArray("steps");
        java.util.Set<String> kept = new java.util.HashSet<>();
        int first = firstProfileStep();
        for (int i = 0; i < first; i++) {
            JSONObject step = steps.optJSONObject(i);
            if (step.optBoolean("keeps_game")) continue;
            kept.add(step.optString("id"));
            if ("each".equals(step.optString("kind"))) {
                try {
                    keep.add(objectsDir(step).getParentFile());
                } catch (Recipe.Failure e) {
                    return;
                }
            }
        }
        if (kept.isEmpty()) return;
        File done = new File(work, "done");
        File[] markers = done.listFiles();
        if (markers != null) for (File m : markers) if (!kept.contains(m.getName())) Instance.deleteTree(m);
        keep.add(done);
        // Objects of later steps (build-info) aren't kept: their markers are gone.
        File obj = new File(work, "obj");
        File[] objDirs = obj.listFiles();
        if (objDirs != null) for (File d : objDirs) if (!kept.contains(d.getName())) Instance.deleteTree(d);
        // ppc/, and ppc_tu/ for a game built with a title update (DAO): the headers stay for build-info.
        for (String name : new String[] { "ppc", "ppc_tu" }) {
            File ppc = new File(work, name);
            File[] sources = ppc.listFiles((d, n) -> n.endsWith(".cpp"));
            if (sources != null) for (File s : sources) Instance.deleteTree(s);
            if (ppc.isDirectory()) keep.add(ppc);
        }
        try {
            touch(new File(work, "update.fingerprint"));
            Files.write(new File(work, "update.fingerprint").toPath(), fingerprint().getBytes(StandardCharsets.UTF_8));
            keep.add(new File(work, "update.fingerprint"));
        } catch (IOException e) {
            android.util.Log.w("XDroid360", "update fingerprint: " + e);
        }
    }

    /** What the kept steps read from the profile: its xr/ and include/ files and the recipe's vars. */
    private String fingerprint() throws IOException {
        java.security.MessageDigest md;
        try {
            md = java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        File profile = Toolchain.profileDir(context, recipe.profileId);
        for (String sub : new String[] { "xr", "include" }) {
            List<File> files = new ArrayList<>();
            listFiles(new File(profile, sub), files);
            files.sort(null);
            for (File f : files) {
                md.update(f.getPath().substring(profile.getPath().length()).getBytes(StandardCharsets.UTF_8));
                md.update(Files.readAllBytes(f.toPath()));
            }
        }
        JSONObject v = recipe.json.optJSONObject("vars");
        md.update(String.valueOf(v).getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static void listFiles(File dir, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File c : children) {
            if (c.isDirectory()) listFiles(c, out);
            else out.add(c);
        }
    }

    // ---------------------------------------------------------------- helpers

    private interface Lines {
        void line(String line);
    }

    private List<String> command(JSONObject step, JSONArray args, Map<String, String> extra) throws Recipe.Failure {
        String tool = step.optString("tool");
        if (!tool.matches("[a-z0-9_]+")) throw new Recipe.Failure("tool_failed", "bad tool name " + tool);
        File exe = new File(Toolchain.tools(context), "lib" + tool + ".so");
        if (!exe.isFile()) throw new Recipe.Failure("tool_failed", "this launcher has no " + exe.getName());
        List<String> argv = new ArrayList<>();
        argv.add(exe.getPath());
        argv.addAll(vars.args(args, extra, objects));
        // lld 22's parallel relocation scan (RelocScan::scan) at times corrupts its heap on the
        // phone: Android aborts it ("Pointer tag ... was truncated") at the link of Dragon Age,
        // and the same link passes on a retry (Xiaomi 12, Adreno 730, 0.1.3). One thread avoids
        // the race, for a few seconds more of a link.
        if ("lld".equals(tool) && argv.contains("gnu")) argv.add("--threads=1");
        return argv;
    }

    /** Runs argv with stdout and stderr in log; lines also go to lines (when given). */
    private int exec(List<String> argv, File cwd, File log, Lines lines) throws IOException, Recipe.Failure {
        if (cancelled.get()) throw new Recipe.Failure("cancelled", "");
        ProcessBuilder pb = new ProcessBuilder(argv).directory(cwd).redirectErrorStream(true);
        pb.environment().put("TMPDIR", new File(work, "tmp").getPath());
        Process p = pb.start();
        running.add(p);
        // The tool's peak memory (VmHWM), into its log: what the recipes' "jobs" numbers come from.
        java.util.concurrent.atomic.AtomicLong peak = new java.util.concurrent.atomic.AtomicLong();
        long pid = pid(p);
        Thread watch = pid > 0 ? new Thread(() -> {
            File status = new File("/proc/" + pid + "/status");
            while (p.isAlive()) {
                long kb = statusKb(status, "VmHWM:");
                if (kb > 0) peak.set(kb);
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "rss " + pid) : null;
        if (watch != null) watch.start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
             Writer w = new FileWriter(log)) {
            w.write(String.join(" ", argv) + "\n\n");
            String line;
            while ((line = r.readLine()) != null) {
                w.write(line);
                w.write('\n');
                if (lines != null) lines.line(line);
            }
            int code = p.waitFor();
            if (peak.get() > 0) w.write("\n[XDroid360] peak RSS " + (peak.get() >> 10) + " MB\n");
            return code;
        } catch (InterruptedException e) {
            p.destroyForcibly();
            throw new Recipe.Failure("cancelled", "");
        } finally {
            running.remove(p);
        }
    }

    private void cancelProcesses() {
        synchronized (running) {
            for (Process p : running) p.destroyForcibly();
        }
    }

    private boolean applies(JSONObject step) throws Recipe.Failure {
        JSONObject when = step.optJSONObject("when");
        if (when == null) return true;
        if (when.has("exists")) return new File(vars.text(when.optString("exists"))).exists();
        if (when.has("missing")) return !new File(vars.text(when.optString("missing"))).exists();
        return true;
    }

    private File marker(JSONObject step) {
        String id = step.optString("id");
        return step.optBoolean("keeps_game") ? new File(job.keptMarkers(context), id) : new File(new File(work, "done"), id);
    }

    private void writeMarker(JSONObject step) throws IOException {
        touch(marker(step));
        // A "rerun" step's marker holds the hash of the tool that did it (rerunCurrent).
        String hash = step.optBoolean("rerun") ? toolHash(step) : null;
        if (hash != null) Files.write(marker(step).toPath(), hash.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Whether a "rerun" step's earlier run still stands: the same tool made it (the hash in its
     * marker) and everything the step lists as "outputs" is there. A step that lists no outputs
     * can't be checked, so it runs again.
     */
    private boolean rerunCurrent(JSONObject step) {
        File marker = marker(step);
        if (!marker.isFile()) return false;
        List<String> outputs = strings(step.optJSONArray("outputs"));
        if (outputs.isEmpty()) return false;
        try {
            for (String output : outputs) {
                if (!new File(vars.text(output)).isFile()) return false;
            }
            String hash = toolHash(step);
            return hash != null && hash.equals(new String(Files.readAllBytes(marker.toPath()), StandardCharsets.UTF_8).trim());
        } catch (IOException | Recipe.Failure e) {
            return false;
        }
    }

    /** The SHA-256 of the tool a step runs (its libNAME.so), or null if it can't be read. */
    private String toolHash(JSONObject step) {
        String tool = step.optString("tool");
        if (tool.isEmpty()) return null;
        File exe = new File(Toolchain.tools(context), "lib" + tool + ".so");
        try (FileInputStream in = new FileInputStream(exe)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1 << 16];
            for (int n; (n = in.read(buffer)) > 0; ) md.update(buffer, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            return null;
        }
    }

    private static void touch(File f) throws IOException {
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), new byte[0]);
    }

    private File objectsDir(JSONObject step) throws Recipe.Failure {
        return new File(vars.text(step.optString("objects", "${WORK}/obj/" + step.optString("id"))));
    }

    private static String objectName(File input) {
        String n = input.getName();
        int dot = n.lastIndexOf('.');
        return (dot > 0 ? n.substring(0, dot) : n) + ".o";
    }

    /** A glob in the file name only ("dir/ppc_recomp.*.cpp"), sorted by name. */
    private static List<File> glob(String pattern) {
        File p = new File(pattern);
        File dir = p.getParentFile();
        StringBuilder re = new StringBuilder();
        for (char c : p.getName().toCharArray()) {
            if (c == '*') re.append(".*");
            else if (c == '?') re.append('.');
            else re.append(Pattern.quote(String.valueOf(c)));
        }
        Pattern name = Pattern.compile(re.toString());
        List<File> out = new ArrayList<>();
        File[] files = dir != null ? dir.listFiles() : null;
        if (files != null) for (File f : files) if (f.isFile() && name.matcher(f.getName()).matches()) out.add(f);
        out.sort((a, b) -> a.getName().compareTo(b.getName()));
        return out;
    }

    private static List<String> strings(JSONArray a) {
        List<String> out = new ArrayList<>();
        if (a != null) for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        return out;
    }

    private static String defaultCode(JSONObject step) {
        String m = step.optString("message");
        if (Recipe.isCode(m)) return m;
        switch (step.optString("kind")) {
            case "extract": return "extract_failed";
            case "each": return "compile_failed";
            case "verify": return "profile_mismatch";
            default: return "tool_failed";
        }
    }

    private static boolean noSpace(IOException e) {
        String m = String.valueOf(e.getMessage());
        return m.contains("ENOSPC") || m.contains("No space left");
    }

    private static String sha256(File f) throws IOException {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] b = new byte[1 << 16];
                int n;
                while ((n = in.read(b)) > 0) sha.update(b, 0, n);
            }
            StringBuilder s = new StringBuilder();
            for (byte x : sha.digest()) s.append(String.format("%02x", x));
            return s.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String mb(long bytes) {
        return (bytes >> 20) + " MB";
    }

    private void report(double withinStep, String detail) {
        listener.onProgress(Math.min(1, (weightDone + withinStep) / weightTotal), stepLabel, detail);
    }

    /** How long a new compile gets to grow before the memory is looked at again. */
    private static final long ADMIT_SETTLE_MS = 2000;

    /** ${JOBS}: the cap on parallel jobs, the core count; memory admits each one (estimateMb). */
    private static int jobs(Recipe recipe) {
        return Math.max(1, Runtime.getRuntime().availableProcessors());
    }

    private long reserveMb() {
        JSONObject j = recipe.json.optJSONObject("jobs");
        return j != null ? j.optLong("reserve_mb", 1024) : 1024;
    }

    /**
     * The memory a compile of input is expected to take: the recipe's optional
     * rss_model (base_mb + per_mb x the input's MiB), else its max_rss_mb.
     */
    private long estimateMb(File input) {
        JSONObject j = recipe.json.optJSONObject("jobs");
        long max = j != null ? j.optLong("max_rss_mb", 600) : 600;
        JSONObject model = j != null ? j.optJSONObject("rss_model") : null;
        if (model == null) return max;
        return (long) (model.optDouble("base_mb", max) + model.optDouble("per_mb", 0) * input.length() / (1024.0 * 1024.0));
    }

    /** A child's pid, from its toString ("Process[pid=1234, hasExited=false]" on Android). */
    private static long pid(Process p) {
        Matcher m = Pattern.compile("pid=(\\d+)").matcher(p.toString());
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    private static long statusKb(File status, String key) {
        try (BufferedReader r = new BufferedReader(new java.io.FileReader(status))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith(key)) return Long.parseLong(line.replaceAll("[^0-9]", ""));
            }
        } catch (IOException | NumberFormatException ignored) {
        }
        return -1;
    }

    private static long memAvailableMb() {
        try (BufferedReader r = new BufferedReader(new java.io.FileReader("/proc/meminfo"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("MemAvailable:")) return Long.parseLong(line.replaceAll("[^0-9]", "")) / 1024;
            }
        } catch (IOException | NumberFormatException ignored) {
        }
        return -1;
    }
}
