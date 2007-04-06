package org.jvnet.annotation_mock_builder;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Nested &lt;classes> elements.
 */
public class Classes {
    Pattern include;
    Pattern exclude;

    public void setIncludes( String pattern ) {
        try {
            include = Pattern.compile(convertToRegex(pattern));
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("Invalid pattern "+pattern,e);
        }
    }

    public void setExcludes( String pattern ) {
        try {
            exclude = Pattern.compile(convertToRegex(pattern));
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("Invalid pattern "+pattern,e);
        }
    }

    private String convertToRegex(String pattern) {
        StringBuilder regex = new StringBuilder();
        char nc;
        if (pattern.length() >0 ) {

            for ( int i = 0 ; i < pattern.length(); i ++ ) {
                char c = pattern.charAt(i);
                int j = i;
                nc = ' ';
                if ((j+1) != pattern.length()) {
                    nc = pattern.charAt(j+1);
                }
                //escape single '.'
                if ((c=='.') && ( nc !='.')){
                    regex.append('\\');
                    regex.append('.');
                    //do not allow patterns like a..b
                } else if ((c=='.') && ( nc =='.')){
                    continue;
                    // "**" gets replaced by ".*"
                } else if ((c=='*') && (nc == '*')) {
                    regex.append(".*");
                    break;
                    //'*' replaced by anything but '.' i.e [^\\.]+
                } else if (c=='*') {
                    regex.append("[^\\.]+");
                    continue;
                    //'?' replaced by anything but '.' i.e [^\\.]
                } else if (c=='?') {
                    regex.append("[^\\.]");
                    //else leave the chars as they occur in the pattern
                } else
                    regex.append(c);
            }

        }

        return regex.toString();
    }
}
