package org.ppojo;

import org.ppojo.utils.EnumParser;

/**
 * Created by GARY on 10/3/2015.
 */
public enum CopyStyleTypes {
    staticMethod("copy",false),
    staticFactory("copy",true),
    copyConstructor("",true),
    memberFactory("copy",true),
    memberCopyTo("copyTo",false),
    memberCopyFrom("copyFrom",false);

    CopyStyleTypes(String defaultMethodName,boolean isFactory) {
        _defaultMethodName=defaultMethodName;
        _isFacotry=isFactory;
    }

    private final String _defaultMethodName;
    private final boolean _isFacotry;

    public String getDefaultMethodName() {
        return _defaultMethodName;
    }

    public boolean isFactory() {
        return _isFacotry;
    }

    private static EnumParser<CopyStyleTypes> _parser=new EnumParser<>(CopyStyleTypes.class,null);

    public static CopyStyleTypes Parse(String capitalizationType) {
        return _parser.Parse(capitalizationType);
    }



}