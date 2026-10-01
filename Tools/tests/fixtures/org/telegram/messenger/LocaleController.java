package org.telegram.messenger;
public class LocaleController { public static LocaleController getInstance(){return new LocaleController();} public java.util.Locale getCurrentLocale(){return java.util.Locale.US;} public static String getString(int id){return "resource-"+id;} }
