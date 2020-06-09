package nctu.winlab.unicastdhcp;

import org.onosproject.core.ApplicationId;
import org.onosproject.net.config.Config;

public class DhcpConfig extends Config<ApplicationId> {

    public static final String LOCATION = "serverLocation";

    @Override
    public boolean isValid() {
        return hasOnlyFields(LOCATION);
    }

    public String location() {
        return get(LOCATION, null);
    }
}
