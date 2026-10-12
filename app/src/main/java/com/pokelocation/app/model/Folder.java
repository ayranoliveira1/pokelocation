package com.pokelocation.app.model;

public final class Folder {
    public static final String FAVORITES = "favorites", ROUTES = "routes";
    public final String id, name, parentId, type;      // parentId null = raiz
    public final int sort;
    public final int depth;                            // 0 fora de FolderDao.tree()

    public Folder(String id, String name, String parentId, String type, int sort, int depth) {
        this.id = id;
        this.name = name;
        this.parentId = parentId;
        this.type = type;
        this.sort = sort;
        this.depth = depth;
    }
}
