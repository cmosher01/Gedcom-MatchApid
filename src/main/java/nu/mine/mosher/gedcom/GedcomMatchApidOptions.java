package nu.mine.mosher.gedcom;

import java.io.File;
import java.io.IOException;

@SuppressWarnings({"access", "WeakerAccess", "unused"})
public class GedcomMatchApidOptions extends GedcomOptions {
    public File gedcom;
    public boolean add;

    public void help() {
        this.help = true;
        System.err.println("Usage: gedcom-matchapid [OPTIONS] <original.ged >out.ged");
        System.err.println("Add _APIDs from Ancestry GEDCOM file.");
        System.err.println("Options:");
        System.err.println("-g, --gedcom=FILE    Ancestry GEDCOM file to extract from.");
        System.err.println("-a, --add-citations  If original citation doesn't exist, add it.");
        options();
    }

    public void g(final String gedcom) throws IOException {
        gedcom(gedcom);
    }

    public void gedcom(final String file) throws IOException {
        this.gedcom = new File(file);
        if (!this.gedcom.canRead()) {
            throw new IllegalArgumentException("Cannot open GEDCOM file: " + this.gedcom.getCanonicalPath());
        }
    }

    public void a() {
        add();
    }

    public void add() {
        this.add = true;
    }

    public GedcomMatchApidOptions verify() {
        if (this.help) {
            return this;
        }
        if (this.gedcom == null) {
            throw new IllegalArgumentException("Missing required -g Ancestry GEDCOM file.");
        }
        if (this.concToWidth == null) {
            throw new IllegalArgumentException("Missing specify -c.");
        }
        return this;
    }
}
