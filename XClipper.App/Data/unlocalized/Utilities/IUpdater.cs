﻿using System;

#nullable enable
namespace Components
{
    public interface IUpdater
    {
        /// <summary>
        /// This method will check for update and will run the method.
        /// </summary>
        /// <param name="block"></param>
        void Check(Action<bool, Update?>? block);

        /// <summary>
        /// This will launch the website for manually downloading update.
        /// // todo: Create a dedicated webpage for showing updates (if possible).
        /// </summary>
        void Launch();
    }
}
