package nanolive.psxj.library;

import java.util.List;

public interface GameLibraryListener {
    void onLibraryUpdated(List<GameEntry> entries);
}
