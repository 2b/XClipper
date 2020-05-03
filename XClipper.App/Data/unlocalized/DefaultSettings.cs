﻿using System.IO;
using System.ComponentModel;
using System;
using static Components.Core;
using System.Xml.Linq;

namespace Components
{
    public static class DefaultSettings
    {

        #region Variable Definitions

        private static string SettingsPath = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "XClipper.xml");
        private const string SETTINGS = "Settings";

        #endregion


        #region Actual Settings

        // This will set the start location of the app.
        public static XClipperLocation AppDisplayLocation { get; set; } = XClipperLocation.BottomRight;
        // This tells what to store meaning only Text, Image, Files or All.
        public static XClipperStore WhatToStore { get; set; } = XClipperStore.All;
        // This tells the number of clip to store.
        public static int TotalClipLength { get; set; } = 20;
        // This tells if Ctrl needs to be pressed in order to activate application.
        public static bool IsCtrl { get; set; } = true;
        // This tells if Alt needs to be pressed in order to activate application.
        public static bool IsAlt { get; set; } = false;
        // This tells if Shift needs to be pressed in order to activate application.
        public static bool IsShift { get; set; } = false;
        // This will set Final Hot key of application.
        public static string HotKey { get; set; } = "Oem3";
        // This will tell if application should start on System Startup.
        public static bool StartOnSystemStartup { get; set; } = true;
        // This will tell if application should play sound when started.
        public static bool PlayNotifySound { get; set; } = true;
        // This will set the current language file to be use.
        public static string CurrentAppLanguage { get; set; } = "locales\\en.xaml";
        // A configuration to password protect database.
        public static bool IsSecureDB { get; set; } = false;
        // A string to hold if purchase complete.
        public static bool IsPurchaseDone { get; set; }
        public static bool UseCustomPassword { get; set; } = false;
        public static string CustomPassword { get; set; } = CONNECTION_PASS.Decrypt();
        public static int TruncateList { get; set; } = 20;

        #endregion


        #region Methods

        public static void WriteSettings()
        {
            var document = new XDocument();
            var settings = new XElement(SETTINGS);
            settings
                .Add(
                    new XElement(nameof(AppDisplayLocation), AppDisplayLocation.ToString()),
                    new XElement(nameof(WhatToStore), WhatToStore.ToString()),
                    new XElement(nameof(TotalClipLength), TotalClipLength.ToString()),
                    new XElement(nameof(IsCtrl), IsCtrl.ToString()),
                    new XElement(nameof(IsAlt), IsAlt.ToString()),
                    new XElement(nameof(IsShift), IsShift.ToString()),
                    new XElement(nameof(HotKey), HotKey.ToString()),
                    new XElement(nameof(StartOnSystemStartup), StartOnSystemStartup.ToString()),
                    new XElement(nameof(PlayNotifySound), PlayNotifySound.ToString()),
                    new XElement(nameof(IsSecureDB), IsSecureDB.ToString()),
                    new XElement(nameof(CurrentAppLanguage), CurrentAppLanguage.ToString()),
                    new XElement(nameof(CustomPassword), CustomPassword.Encrypt()),
                    new XElement(nameof(UseCustomPassword), UseCustomPassword.ToString())
                    );
            document.Add(settings);
            document.Save(SettingsPath);
        }

        public static void LoadSettings()
        {
            if (!File.Exists(SettingsPath)) return;  // Return if settings does not exist, so it will use defaults

            var settings = XDocument.Load(SettingsPath).Element(SETTINGS);

            AppDisplayLocation = settings.Element(nameof(AppDisplayLocation)).Value.ToEnum<XClipperLocation>();
            WhatToStore = settings.Element(nameof(WhatToStore)).Value.ToEnum<XClipperStore>();
            TotalClipLength = settings.Element(nameof(TotalClipLength)).Value.ToInt();
            IsCtrl = settings.Element(nameof(IsCtrl)).Value.ToBool();
            IsAlt = settings.Element(nameof(IsAlt)).Value.ToBool();
            IsShift = settings.Element(nameof(IsShift)).Value.ToBool();
            HotKey = settings.Element(nameof(HotKey)).Value;
            CustomPassword = settings.Element(nameof(CustomPassword)).Value.Decrypt();
            IsSecureDB = settings.Element(nameof(IsSecureDB)).Value.ToBool();
            UseCustomPassword = settings.Element(nameof(UseCustomPassword)).Value.ToBool();
            CurrentAppLanguage = settings.Element(nameof(CurrentAppLanguage)).Value;
            StartOnSystemStartup = settings.Element(nameof(StartOnSystemStartup)).Value.ToBool();
            PlayNotifySound = settings.Element(nameof(PlayNotifySound)).Value.ToBool();
            
        }

        #endregion

    }

    #region Setting Enums

    public enum XClipperStore
    {
        [Description("Text Only")]
        Text = 0,
        [Description("Image Only")]
        Image = 1,
        [Description("Files Only")]
        Files = 2,
        [Description("Everything")]
        All = 3
    }

    public enum XClipperLocation
    {
        [Description("Bottom Right")]
        BottomRight = 0,
        [Description("Bottom Left")]
        BottomLeft = 1,
        [Description("Top Right")]
        TopRight = 2,
        [Description("Top Left")]
        TopLeft = 3,
        [Description("Center")]
        Center = 4
    }

    #endregion
}
