package com.ldtteam.minecolonieswikigenerator.research;

public enum ResearchObjectType
{
    RESEARCH("Research", "research"),
    RESEARCH_TREE("Research Trees", "research_trees"),
    RESEARCH_EFFECT("Research Effects", "research_effects");

    private final String displayName;

    private final String folder;

    ResearchObjectType(final String displayName, final String folder)
    {
        this.displayName = displayName;
        this.folder = folder;
    }

    public String getDisplayName()
    {
        return displayName;
    }

    public String getFolder()
    {
        return folder;
    }
}
