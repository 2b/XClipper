﻿using System;

namespace Components
{
    public static class ValueConversionExtensions
    {
        #region Strings

        public static int ToInt(this string s) => Convert.ToInt32(s);
        public static double ToDouble(this string s) => Convert.ToDouble(s);
        public static float ToFloat(this string s) => Convert.ToSingle(s);
        public static bool ToBool(this string s) => Convert.ToBoolean(s);

        #endregion
    }
}
