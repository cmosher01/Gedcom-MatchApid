package nu.mine.mosher.gedcom;

import nu.mine.mosher.collection.TreeNode;
import nu.mine.mosher.gedcom.exception.InvalidLevel;
import nu.mine.mosher.mopper.ArgParser;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static nu.mine.mosher.logging.Jul.log;

// Created by Christopher Alan Mosher on 2017-09-16

public class GedcomMatchApid implements Gedcom.Processor {
    private final GedcomMatchApidOptions options;
    private GedcomTree tree;
    private GedcomTree ancestry;

    private final Counts c = new Counts();

    private final List<ChildToBeAdded> newNodes = new ArrayList<>(256);

    private static class ChildToBeAdded {
        TreeNode<GedcomLine> parent;
        TreeNode<GedcomLine> child;

        ChildToBeAdded(TreeNode<GedcomLine> parent, TreeNode<GedcomLine> child) {
            this.parent = parent;
            this.child = child;
        }
    }


    public static void main(final String... args) throws InvalidLevel, IOException {
        log();
        final GedcomMatchApidOptions options = new ArgParser<>(new GedcomMatchApidOptions()).parse(args).verify();
        new Gedcom(options, new GedcomMatchApid(options)).main();
        System.out.flush();
        System.err.flush();
    }



    private GedcomMatchApid(final GedcomMatchApidOptions options) {
        this.options = options;
    }



    @Override
    public boolean process(final GedcomTree tree) {
        this.tree = tree;
        readGedcom();
        matchApids();
        this.newNodes.forEach(a -> a.parent.addChild(a.child));
        this.c.logAsWarning();
        log().warning(String.format(Counts.format, "Total new lines added to GEDCOM", this.newNodes.size()));
        return true;
    }

    private void readGedcom() {
        try {
            this.ancestry = Gedcom.readFile(new BufferedInputStream(new FileInputStream(this.options.gedcom)));
        } catch (final Throwable e) {
            throw new IllegalArgumentException(e);
        }

        new GedcomConcatenator(this.ancestry).concatenate();
    }

    private static class Counts {
        final AtomicInteger eventsWithApidTotal = new AtomicInteger();
        final AtomicInteger eventsWithApidNotMatched = new AtomicInteger();
        final AtomicInteger eventsWithApidMatched = new AtomicInteger();

        final AtomicInteger apidsTotal = new AtomicInteger();
        final AtomicInteger apidsNotMatched = new AtomicInteger();
        final AtomicInteger apidsAlreadyExisted = new AtomicInteger();
        final AtomicInteger apidsAdded = new AtomicInteger();

        public static final String format = "%35s: %7d";

        void logAsWarning() {
            log().warning("");
            log().warning(String.format(format, "Total count of events with _APID", this.eventsWithApidTotal.get()));
            log().warning(String.format(format, "    count of unmatched", this.eventsWithApidNotMatched.get()));
            log().warning(String.format(format, "    count of matched", this.eventsWithApidMatched.get()));
            if (this.eventsWithApidTotal.get() != this.eventsWithApidMatched.get() + this.eventsWithApidNotMatched.get()) {
                log().severe("ERROR: Event numbers don't add up correctly.");
            }
            log().warning("");
            log().warning(String.format(format, "Total count of _APIDs", this.apidsTotal.get()));
            log().warning(String.format(format, "    count of unmatched", this.apidsNotMatched.get()));
            log().warning(String.format(format, "    count already in file", this.apidsAlreadyExisted.get()));
            log().warning(String.format(format, "    count added to file", this.apidsAdded.get()));
            if (this.apidsTotal.get() != this.apidsAdded.get() + this.apidsAlreadyExisted.get() + this.apidsNotMatched.get()) {
                log().severe("ERROR: _APID numbers don't add up correctly.");
            }
        }
    }

    private void matchApids() {
        this.ancestry.getRoot().forEach(r -> {
            if (r.getObject().getTag().equals(GedcomTag.INDI) || r.getObject().getTag().equals(GedcomTag.FAM)) {
                r.forEach(event -> {
                    final HashMap<String, ArrayList<TreeNode<GedcomLine>>> mapSourIdToCita = citationsById(event, false);
                    if (mapSourIdToCita.size() > 0) {
                        this.c.eventsWithApidTotal.incrementAndGet();
                        countApidsInEvent(event, this.c.apidsTotal);
                        final TreeNode<GedcomLine> eventOrig = matchOrigEvent(event);
                        if (eventOrig != null) {
                            matchOrigApids(event, eventOrig);
                            this.c.eventsWithApidMatched.incrementAndGet();
                        } else {
                            countApidsInEvent(event, this.c.apidsNotMatched);
                            this.c.eventsWithApidNotMatched.incrementAndGet();
                        }
                    }
                });
            }
        });
    }

    private static void countApidsInEvent(final TreeNode<GedcomLine> event, final AtomicInteger c) {
        for (final TreeNode<GedcomLine> cita : event) {
            c.addAndGet(countChildren(cita, "_APID"));
        }
    }

    private TreeNode<GedcomLine> matchOrigEvent(final TreeNode<GedcomLine> eventAnc) {
        final String id = eventAnc.parent().getObject().getID();


        /* get INDI/FAM (match on ID) from original file, and look for matching event */
        final TreeNode<GedcomLine> orig = this.tree.getNode(id);
        if (orig == null) {
            logNoMatchRecord(eventAnc.parent());
            return null;
        }

        final ArrayList<TreeNode<GedcomLine>> eventsOrig = new ArrayList<>(4);
        orig.forEach(eventOrig -> {
            if (eventsMatch(eventAnc, eventOrig)) {
                eventsOrig.add(eventOrig);
            }
        });

        if (eventsOrig.size() < 1) {
            logNoMatchEvent(eventAnc);
        } else if (eventsOrig.size() > 1) {
            logMultipleMatchEvents(eventAnc, eventsOrig);
        }

        return eventsOrig.size() == 1 ? eventsOrig.get(0) : null;
    }

    private void matchOrigApids(final TreeNode<GedcomLine> eventAnc, final TreeNode<GedcomLine> eventOrig) {
        final HashMap<String, ArrayList<TreeNode<GedcomLine>>> mapSourIdToCitasOrig = citationsById(eventOrig, true);
        eventAnc.forEach(citaAnc -> {
            final GedcomLine lineCitaAnc = citaAnc.getObject();
            if (lineCitaAnc.getTag().equals(GedcomTag.SOUR) && (getChild(citaAnc, "_APID") != null)) {
                /* For every Ancestry citation with an _APID: */
                final ArrayList<TreeNode<GedcomLine>> citasOrig = mapSourIdToCitasOrig.get(lineCitaAnc.getPointer());
                if (citasOrig == null) {
                        /*
                        First eliminate the improbable situation where the Ancestry
                        citation has more than one _APID.
                         */
                    if (countChildren(citaAnc, "_APID") != 1) {
                        log().warning("Skipping; found multiple _APID records for citation in Ancestry file: " + msgFor(citaAnc));
                        this.c.apidsNotMatched.addAndGet(countChildren(citaAnc, "_APID"));
                    } else {
                        // Check all citations and see if it's already on one of them
                        final String apid = getChildValue(citaAnc, "_APID");
                        boolean found = false;
                        for (final TreeNode<GedcomLine> citaOrig : eventOrig) {
                            if (anyChildHas(citaOrig, "_APID", apid)) {
                                found = true;
                            }
                        }
                        if (found) {
                            this.c.apidsAlreadyExisted.incrementAndGet();
                            apidBug(apid);
                        } else {
                            if (this.options.add && originalExists(lineCitaAnc.getPointer())) {
                                addNewCitation(lineCitaAnc.getPointer(), apid, eventOrig);
                                this.c.apidsAdded.incrementAndGet();
                            } else {
                                log().warning("Cannot find original citation: " + msgFor(citaAnc) + msgFor(eventOrig));
                                this.c.apidsNotMatched.incrementAndGet();
                            }
                        }
                    }
                } else if (citasOrig.size() > 1) {
                    /* Found multiple citations in original. */
                    /* First see if we can narrow it down to one by matching on page */
                    int c = 0;
                    final String pageAnc = getChildValue(citaAnc, GedcomTag.PAGE);
                    if (!pageAnc.isEmpty()) {
                        for (final TreeNode<GedcomLine> citaOrig : citasOrig) {
                            final String pageOrig = getChildValue(citaOrig, GedcomTag.PAGE);
                            if (ancestryPagesMatch(pageAnc, pageOrig)) {
                                ++c;
                            }
                        }
                    }
                    if (c == 1) {
                        for (final TreeNode<GedcomLine> citaOrig : citasOrig) {
                            final String pageOrig = getChildValue(citaOrig, GedcomTag.PAGE);
                            if (ancestryPagesMatch(pageAnc, pageOrig)) {
                                addApidAndCountIt(citaAnc, citaOrig);
                            }
                        }
                    } else if (c == 0) {
                        /* strange corner case where no FTM page matching Ancestry page */
                        this.c.apidsNotMatched.incrementAndGet();
                        log().warning("No original citation found: " + msgFor(citaAnc));
                        if (!pageAnc.isEmpty()) {
                            log().warning("                           Ancestry PAGE " + pageAnc);
                            for (final TreeNode<GedcomLine> citaOrig : citasOrig) {
                                final String pageOrig = getChildValue(citaOrig, GedcomTag.PAGE);
                                log().warning("                           Original PAGE " + pageOrig);
                            }
                        }
                    } else {
                        /*
                        First eliminate the improbable situation where the Ancestry
                        citation has more than one _APID.
                         */
                        if (countChildren(citaAnc, "_APID") != 1) {
                            log().warning("Skipping; found multiple _APID records for citation in Ancestry file: " + msgFor(citaAnc));
                            this.c.apidsNotMatched.addAndGet(countChildren(citaAnc, "_APID"));
                        } else {
                            /*
                            If we can't narrow it down by matching on page,
                            then check here to see if any of them already have the
                            _APID, just so we don't count it among the non-matching ones.
                             */
                            final String apid = getChildValue(citaAnc, "_APID");
                            boolean found = false;
                            for (final TreeNode<GedcomLine> citaOrig : citasOrig) {
                                if (anyChildHas(citaOrig, "_APID", apid)) {
                                    found = true;
                                }
                            }
                            if (found) {
                                this.c.apidsAlreadyExisted.incrementAndGet();
                            } else {
                                log().warning("Found ambiguous original citations: " + msgFor(citaAnc));
                                if (!pageAnc.isEmpty()) {
                                    log().warning("                                    PAGE " + pageAnc);
                                }
                                this.c.apidsNotMatched.incrementAndGet();
                            }
                        }
                    }
                } else {
                    // single matching original citation
                    final TreeNode<GedcomLine> citaOrig = citasOrig.get(0);
                    addApidAndCountIt(citaAnc, citaOrig);
                }
            }
        });
    }

    private static final Pattern PAT_APID = Pattern.compile("(\\d+,\\d+::)(\\d+)(?:.*)");

    private String apidBug(String apid) {
        final Matcher matcher = PAT_APID.matcher(apid);
        if (!matcher.matches()) {
            log().warning("Detected unparsable _APID. Replacing with __TODO__, needs to be fixed manually.");
            return "__TODO__";
        }

        try {
            final long id = Long.parseLong(matcher.group(2));
            if (id == 2147483647) {
                log().warning("Detected _APID with 2147483647, which is most likely due to a bug from Ancestry.com's export. Replacing with __TODO__, needs to be fixed manually.");
                return matcher.group(1) + "__TODO__";
            }
        } catch (final Throwable e) {
            log().log(Level.WARNING, "Invalid _APID: "+apid+" Replacing with __TODO__", e);
            return "__TODO__";
        }

        return matcher.group(1)+matcher.group(2);
    }

    private static final int ANCESTRY_CITA_LEN_MAX = 256;
    private boolean ancestryPagesMatch(final String pageAnc, final String pageOrig) {
        /* ancestry truncates citation PAGE values to 256 characters */
        final String orig = pageOrig.length() > ANCESTRY_CITA_LEN_MAX ? pageOrig.substring(0, ANCESTRY_CITA_LEN_MAX) : pageOrig;
        return orig.equals(pageAnc);
    }

    private void addNewCitation(final String idSour, String apidValue, final TreeNode<GedcomLine> eventOrig) {
        apidValue = apidBug(apidValue);
        final GedcomLine cita = GedcomLine.createPointer(eventOrig.getObject().getLevel()+1, GedcomTag.SOUR, idSour);
        final TreeNode<GedcomLine> nodeCita = new TreeNode<>(cita);
        final GedcomLine apid = cita.createChild("_APID", apidValue);
        final TreeNode<GedcomLine> nodeApid = new TreeNode<>(apid);
        nodeCita.addChild(nodeApid);
        this.newNodes.add(new ChildToBeAdded(eventOrig, nodeCita));
    }

    private boolean originalExists(final String id) {
        return this.tree.getNode(id) != null;
    }

    private void addApidAndCountIt(final TreeNode<GedcomLine> citaAnc, final TreeNode<GedcomLine> citaOrig) {
        int c = addApidSafely(citaAnc, citaOrig);
        if (c < 0) {
            this.c.apidsNotMatched.addAndGet(c);
        } else if (c > 0) {
            this.c.apidsAdded.addAndGet(c);
        } else {
            this.c.apidsAlreadyExisted.incrementAndGet();
        }
    }

    /**
     * @param citaAnc
     * @param citaOrig
     * @return 0 if one _APID and it already existed
     * 1 if added new _APID successfully
     * -c if _APID records not added (c is count of records)
     */
    private int addApidSafely(final TreeNode<GedcomLine> citaAnc, final TreeNode<GedcomLine> citaOrig) {
        // some sanity checks first

        // ensure source citation has one and only one _APID
        // I've never seen this case before, but better safe than sorry.
        int cApidAncNotMatched = countChildren(citaAnc, "_APID");
        if (cApidAncNotMatched != 1) {
            log().warning("Skipping; found multiple _APID records for citation in Ancestry file: " + msgFor(citaAnc));
            return -cApidAncNotMatched;
        }
        assert cApidAncNotMatched == 1;
        final String apidAnc = getChildValue(citaAnc, "_APID");

        // ensure at most one _APID in original
        if (countChildren(citaOrig, "_APID") > 1) {
            log().warning("Found multiple _APID records for citation in original file: " + msgFor(citaOrig));
            if (anyChildHas(citaOrig, "_APID", apidAnc)) {
                log().warning("    but the one from Ancestry is already in there: " + apidAnc);
                apidBug(apidAnc); //just log
                cApidAncNotMatched = 0;
            } else {
                log().warning("    even though none of them match, we still won't add the new one: " + apidAnc);
                apidBug(apidAnc); //just log
            }
            return -cApidAncNotMatched;
        }
        assert cApidAncNotMatched == 1;
        final String apidOrig = getChildValue(citaOrig, "_APID");

        // now add it, if it's not already there (and we aren't already adding it)
        if (apidOrig.equals(apidAnc) || apidPendingAdd(citaOrig, apidAnc)) {
            apidBug(apidAnc); // just log
            // Ancestry _APID is already in original file (or will be added); OK, do nothing
            cApidAncNotMatched = 0;
        } else {
            addApidForced(apidAnc, citaOrig);
        }

        return cApidAncNotMatched;
    }

    private boolean apidPendingAdd(final TreeNode<GedcomLine> citaOrig, final String apidAnc) {
        for (final ChildToBeAdded a : this.newNodes) {
            if (a.parent == citaOrig && a.child.getObject().getValue().equals(apidAnc)) {
                return true;
            }
        }
        return false;
    }

    private void addApidForced(String apidAnc, final TreeNode<GedcomLine> citaOrig) {
        apidAnc = apidBug(apidAnc);
        this.newNodes.add(new ChildToBeAdded(citaOrig, new TreeNode<>(citaOrig.getObject().createChild("_APID", apidAnc))));
        log().finer("Added _APID " + apidAnc + " to original: " + msgFor(citaOrig));
    }



    private void logMultipleMatchEvents(final TreeNode<GedcomLine> eventAnc, final ArrayList<TreeNode<GedcomLine>> eventsOrig) {
        log().warning("Multiple events matched " + msgFor(eventAnc));
        eventsOrig.forEach(e -> log().warning("    " + msgFor(e)));
    }

    private void logNoMatchEvent(final TreeNode<GedcomLine> eventAnc) {
        log().warning("Could not match " + msgFor(eventAnc));
    }

    private void logNoMatchRecord(final TreeNode<GedcomLine> r) {
        log().warning("Could not match " + msgFor(r));
    }

    private static void warnLostApid(final TreeNode<GedcomLine> sour) {
        final String apid = getChildValue(sour, "_APID");
        if (!apid.isEmpty()) {
            log().warning("Could find match for _APID " + apid);
        }
    }

    private static String msgFor(final TreeNode<GedcomLine> node) {
        String msg = "";
        if (node != null) {
            msg += msgFor(node.parent()) + " | ";
            final GedcomLine g = node.getObject();
            if (g != null) {
                final GedcomTag t = g.getTag();
                if (t.equals(GedcomTag.INDI)) {
                    final String name = getChildValue(node, GedcomTag.NAME);
                    msg += "INDI " + g.getID() + " " + name;
                } else if (t.equals(GedcomTag.SOUR)) {
                    msg += g.getTagString();
                    if (g.hasID()) {
                        msg += " " + g.getID();
                    } else if (g.isPointer()) {
                        msg += " " + g.getPointer();
                        final String apid = getChildValue(node, "_APID");
                        if (!apid.isEmpty()) {
                            msg += " <--- _APID "+apid;
                        }
                    } else {
                        msg += " " + g.getValue();
                    }
                } else {
                    msg += g.getTagString();
                    if (g.hasID()) {
                        msg += " " + g.getID();
                    } else if (g.isPointer()) {
                        msg += " " + g.getPointer();
                    } else {
                        msg += " " + g.getValue();
                    }
                }
                String d = "";
                if (!(d = getChildValue(node, GedcomTag.DATE)).isEmpty()) {
                    msg += " date: " + d;
                }
            }
        }
        return msg;
    }

    private static HashMap<String, ArrayList<TreeNode<GedcomLine>>> citationsById(final TreeNode<GedcomLine> event, final boolean all) {
        final HashMap<String, ArrayList<TreeNode<GedcomLine>>> map = new HashMap<>();
        event.forEach(s -> {
            final GedcomLine sour = s.getObject();
            if (sour.getTag().equals(GedcomTag.SOUR)) {
                if (all || (getChild(s, "_APID") != null)) {
                    final String id = sour.getPointer();
                    map.putIfAbsent(id, new ArrayList<>());
                    map.get(id).add(s);
                }
            }
        });
        return map;
    }

    private static boolean eventsMatch(TreeNode<GedcomLine> eventAnc, TreeNode<GedcomLine> eventOrig) {
        final GedcomLine lineAnc = eventAnc.getObject();
        final GedcomLine lineOrig = eventOrig.getObject();

        if (lineAnc == null || lineOrig == null) {
            return false;
        }

        if (!lineAnc.getTagString().equals(lineOrig.getTagString())) {
            return false;
        }

        String type = "";
        if (lineAnc.getTag().equals(GedcomTag.EVEN)) {
            // check TYPEs of generic EVEN items
            type = getChildValue(eventAnc, GedcomTag.TYPE).toLowerCase();
            final String typeNew = getChildValue(eventOrig, GedcomTag.TYPE).toLowerCase();
            if (!type.equals(typeNew)) {
                return false;
            }
        }

        if (isUnique(lineAnc.getTag(), type, eventOrig.parent())) {
            return true;
        }

        if (lineAnc.getTag().equals(GedcomTag.NAME)) {
            final String val = lineAnc.getValue();
            final String valNew = lineOrig.getValue();
            if (!val.equals(valNew)) {
                return false;
            }
        }

        final String date = getChildValue(eventAnc, GedcomTag.DATE);
        final String dateNew = getChildValue(eventOrig, GedcomTag.DATE);

        if (date.isEmpty() && dateNew.isEmpty()) {
            // match on place instead
        } else if (!date.equals(dateNew)) {
            return false;
        }

        final String place = getChildValue(eventAnc, GedcomTag.PLAC).toLowerCase();
        final String place1 = place.split(",")[0];
        final String placeNew = getChildValue(eventOrig, GedcomTag.PLAC).toLowerCase();
        final String placeNew1 = placeNew.split(",")[0];
        if (!place.equals(placeNew) && !place.contains(placeNew1) && !placeNew.contains(place1)) {
            return false;
        }

        // TODO: check values of other types, too?
        if (lineAnc.getTag().equals(GedcomTag.DSCR)) {
            if (!lineAnc.getValue().equals(lineOrig.getValue())) {
                return false;
            }
        }

        return true;
    }

    private static boolean isUnique(final GedcomTag tag, final String even, final TreeNode<GedcomLine> obj) {
        int cTag = 0;
        for (final TreeNode<GedcomLine> c : obj) {
            if (tag.equals(GedcomTag.EVEN)) {
                final String typeNew = getChildValue(c, GedcomTag.TYPE).toLowerCase();
                if (even.equals(typeNew)) {
                    ++cTag;
                    if (cTag > 1) {
                        return false;
                    }
                }
            } else if (c.getObject().getTag().equals(tag)) {
                ++cTag;
                if (cTag > 1) {
                    return false;
                }
            }
        }
        return true;
    }

    private static String getChildValue(final TreeNode<GedcomLine> item, final GedcomTag tag) {
        return getChildValue(item, tag.toString());
    }

    private static String getChildValue(final TreeNode<GedcomLine> item, final String tag) {
        final TreeNode<GedcomLine> c = getChild(item, tag);
        if (c == null) {
            return "";
        }

        final GedcomLine gedcomLine = c.getObject();
        return gedcomLine.isPointer() ? gedcomLine.getPointer() : gedcomLine.getValue();
    }

    private static TreeNode<GedcomLine> getChild(final TreeNode<GedcomLine> item, final GedcomTag tag) {
        return getChild(item, tag.toString());
    }

    private static TreeNode<GedcomLine> getChild(final TreeNode<GedcomLine> item, final String tag) {
        for (final TreeNode<GedcomLine> c : item) {
            final GedcomLine gedcomLine = c.getObject();
            if (gedcomLine.getTagString().equals(tag)) {
                return c;
            }
        }
        return null;
    }

    private static int countChildren(final TreeNode<GedcomLine> node, final GedcomTag tag) {
        return countChildren(node, tag.toString());
    }

    private static int countChildren(final TreeNode<GedcomLine> node, final String tag) {
        int c = 0;
        for (final TreeNode<GedcomLine> child : node) {
            final GedcomLine gedcomLine = child.getObject();
            if (gedcomLine.getTagString().equals(tag)) {
                ++c;
            }
        }
        return c;
    }

    private static boolean anyChildHas(final TreeNode<GedcomLine> node, final String tag, final String value) {
        for (final TreeNode<GedcomLine> child : node) {
            final GedcomLine gedcomLine = child.getObject();
            if (gedcomLine.getTagString().equals(tag) && gedcomLine.getValue().equals(value)) {
                return true;
            }
        }
        return false;
    }
}
