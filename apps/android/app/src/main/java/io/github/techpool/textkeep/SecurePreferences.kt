package io.github.techpool.textkeep

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import org.json.JSONArray

class SecurePreferences(private val source:SharedPreferences,private val namespace:String):SharedPreferences {
 private val listeners=java.util.concurrent.ConcurrentHashMap<SharedPreferences.OnSharedPreferenceChangeListener,SharedPreferences.OnSharedPreferenceChangeListener>()
 private fun context(key:String)="firebase-prefs:$namespace:$key"
 private fun value(key:String):Any? {val encrypted=source.getString(key,null)?:return null;val obj=JSONObject(LocalCrypto.decrypt(encrypted,context(key)));return when(obj.getString("type")){"string"->obj.getString("value");"int"->obj.getInt("value");"long"->obj.getLong("value");"float"->obj.getDouble("value").toFloat();"boolean"->obj.getBoolean("value");"set"->obj.getJSONArray("value").let{arr->(0 until arr.length()).map{arr.getString(it)}.toSet()};else->null}}
 override fun getAll():MutableMap<String,*> =source.all.keys.associateWith{value(it)}.toMutableMap()
 override fun getString(key:String,def:String?):String?=value(key) as? String?:def
 override fun getStringSet(key:String,def:MutableSet<String>?):MutableSet<String>?=(value(key) as? Set<*>)?.filterIsInstance<String>()?.toMutableSet()?:def
 override fun getInt(key:String,def:Int)=value(key) as? Int?:def
 override fun getLong(key:String,def:Long)=value(key) as? Long?:def
 override fun getFloat(key:String,def:Float)=value(key) as? Float?:def
 override fun getBoolean(key:String,def:Boolean)=value(key) as? Boolean?:def
 override fun contains(key:String)=source.contains(key)
 override fun registerOnSharedPreferenceChangeListener(listener:SharedPreferences.OnSharedPreferenceChangeListener){val wrapped=SharedPreferences.OnSharedPreferenceChangeListener{_,key->listener.onSharedPreferenceChanged(this,key)};listeners.put(listener,wrapped)?.let{source.unregisterOnSharedPreferenceChangeListener(it)};source.registerOnSharedPreferenceChangeListener(wrapped)}
 override fun unregisterOnSharedPreferenceChangeListener(listener:SharedPreferences.OnSharedPreferenceChangeListener){listeners.remove(listener)?.let{source.unregisterOnSharedPreferenceChangeListener(it)}}
 override fun edit():SharedPreferences.Editor=Editor(source.edit())
 inner class Editor(private val edit:SharedPreferences.Editor):SharedPreferences.Editor {
  private fun put(key:String,type:String,value:Any?):SharedPreferences.Editor {if(value==null)edit.remove(key)else edit.putString(key,LocalCrypto.encrypt(JSONObject().put("type",type).put("value",value).toString(),context(key)));return this}
  override fun putString(key:String,value:String?)=put(key,"string",value)
  override fun putStringSet(key:String,value:MutableSet<String>?)=put(key,"set",value?.let{JSONArray(it.toList())})
  override fun putInt(key:String,value:Int)=put(key,"int",value)
  override fun putLong(key:String,value:Long)=put(key,"long",value)
  override fun putFloat(key:String,value:Float)=put(key,"float",value)
  override fun putBoolean(key:String,value:Boolean)=put(key,"boolean",value)
  override fun remove(key:String):SharedPreferences.Editor{edit.remove(key);return this}
  override fun clear():SharedPreferences.Editor{edit.clear();return this}
  override fun commit()=edit.commit()
  override fun apply()=edit.apply()
 }
}
