// File-override mods (DAORecomp's mods/README.txt): each folder under <instance>/mods/ mirrors the
// game folder (mods/MyMod/packages/core/override/foo.gda). The enabled ones are layered over the
// disc in load order, later mods winning. The mods page (ModsActivity) keeps the order and the
// on/off state in mods/load_order.txt, one folder per line: "+Folder" or "-Folder". Without that
// file the config.toml's [[mods]] list is used (as the desktop launcher writes it).
#include "stdafx.h"

#include <algorithm>
#include <fstream>
#include <set>

#include "kernel/vfs.h"
#include "settings.h"

namespace fs = std::filesystem;

namespace dao_android
{
    // Layers the enabled mods over the disc. Before the game reads its files.
    void ApplyMods(const fs::path& gameRoot)
    {
        const fs::path modsRoot = gameRoot.parent_path() / "mods";
        std::error_code ec;

        // The folders there (names starting with "." are the app's work folders, not mods).
        std::set<std::string> onDisk;
        for (fs::directory_iterator it(modsRoot, ec), end; !ec && it != end; it.increment(ec))
        {
            const std::string name = it->path().filename().string();
            if (!name.empty() && name[0] != '.' && it->is_directory(ec))
                onDisk.insert(name);
        }

        struct Mod { std::string folder; bool enabled; };
        std::vector<Mod> order;
        std::ifstream file(modsRoot / "load_order.txt");
        if (file.is_open())
        {
            std::string line;
            while (std::getline(file, line))
            {
                if (!line.empty() && line.back() == '\r')
                    line.pop_back();
                if (line.size() > 1 && (line[0] == '+' || line[0] == '-'))
                    order.push_back({ line.substr(1), line[0] == '+' });
            }
        }
        else
        {
            for (const auto& mod : dao::GetSettings().mods)
                order.push_back({ mod.folder, mod.enabled });
        }

        // In load order; folders not listed yet are enabled, after the listed ones.
        std::vector<fs::path> loadOrder;
        for (const Mod& mod : order)
        {
            if (onDisk.erase(mod.folder) && mod.enabled)
                loadOrder.push_back(modsRoot / mod.folder);
        }
        for (const std::string& name : onDisk)
            loadOrder.push_back(modsRoot / name);

        LOGI("Mods: {} enabled", loadOrder.size());
        // Highest priority first: the last mod in the load order wins.
        std::reverse(loadOrder.begin(), loadOrder.end());
        dao::kernel::VfsSetOverlays("\\Device\\Cdrom0", std::move(loadOrder));
    }
}
